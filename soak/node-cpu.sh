#!/usr/bin/env bash
# Sample the ARM NODE's per-CPU time while a ladder runs, so work the pod's
# cgroup cannot see is not mistaken for the pod being idle.
#
#   soak/node-cpu.sh OUT.tsv [NODE_IP]            # sampler, run detached
#   soak/node-cpu.sh --report JOB.log OUT.tsv     # align samples to each step
#
# Why this exists: an arm holding 1.45 of its 2-core quota with half a core
# apparently idle is only idle INSIDE the cgroup. Kernel work — softirq for
# network receive, VXLAN encapsulation, the CNI path — is charged to the node,
# not to the pod, and on this cluster (Raspberry Pi CM5, 4 cores per node,
# flannel VXLAN) that work is large and lands on one CPU: a single NIC receive
# queue puts ~65% of the node's lifetime softirq on cpu0. A saturated cpu0 caps
# throughput while every pod-level number still shows headroom.
#
# Prometheus here has no node-exporter (the kubernetes-* scrape jobs are off),
# but Talos publishes per-CPU counters natively, so this needs nothing deployed
# to the cluster: `talosctl get cpustat` returns user/system/softIrq/irq/idle
# per CPU, cumulative, which differences cleanly across a step window.
#
# Sample format, one line per sample:
#   <epoch> cpu<N> <user> <system> <softIrq> <irq> <idle>
set -uo pipefail

if [ "${1:-}" = "--report" ]; then
  log="${2:?usage: node-cpu.sh --report JOB.log SAMPLES.tsv}"
  tsv="${3:?}"
  reader=cat; case "${log}" in *.gz) reader=zcat ;; esac
  # Same discipline as client-cpu.sh: divide by the span actually sampled, not
  # the requested window, and refuse a window with fewer than two samples
  # rather than print a confident zero.
  ${reader} "${log}" | grep '^#NH-STEP' | sed 's/^#NH-STEP //' |
    jq -r '"\(.rps) \(.warmup) \(.start) \(.end)"' |
    while read -r rps warm start end; do
      awk -v rps="${rps}" -v warm="${warm}" -v s="${start}" -v e="${end}" '
        $1 >= s && $1 <= e && NF == 7 {
          c = $2
          if (!(c in t0)) { t0[c] = $1; u0[c] = $3; s0[c] = $4; q0[c] = $5; i0[c] = $6; d0[c] = $7 }
          t1[c] = $1; u1[c] = $3; s1[c] = $4; q1[c] = $5; i1[c] = $6; d1[c] = $7
          if (!(c in seen)) { seen[c] = 1; order[++n] = c }
        }
        END {
          tag = (warm == "true") ? " (warmup)" : ""
          if (n == 0) { printf "%-16s   n/a (no samples in window)\n", rps tag; exit }
          span = 0; busy = 0; soft = 0; softmax = 0; softmaxcpu = ""
          for (k = 1; k <= n; k++) {
            c = order[k]
            if (t1[c] == t0[c]) { printf "%-16s   n/a (one sample in window)\n", rps tag; exit }
            sp = t1[c] - t0[c]; if (sp > span) span = sp
            du = u1[c]-u0[c]; ds = s1[c]-s0[c]; dq = q1[c]-q0[c]; di = i1[c]-i0[c]
            busy += du + ds + dq + di
            soft += dq
            if (dq > softmax) { softmax = dq; softmaxcpu = c }
          }
          printf "%-16s %5.2f node cores  %5.2f softirq  (busiest %s at %.2f)  (%ds sampled)\n", \
                 rps tag, busy/span, soft/span, softmaxcpu, softmax/span, span
        }' "${tsv}"
    done
  exit 0
fi

out="${1:?usage: node-cpu.sh OUT.tsv [NODE_IP]}"
node="${2:-192.168.0.61}"   # main-worker-02, where the 2-CPU arms run
while true; do
  talosctl -n "${node}" get cpustat -o yaml 2>/dev/null |
    python3 -c '
import sys, yaml, time
try:
    d = yaml.safe_load(sys.stdin)
    now = int(time.time())
    for i, c in enumerate(d["spec"]["cpu"]):
        vals = [c["user"], c["system"], c["softIrq"], c["irq"], c["idle"]]
        print(now, "cpu%d" % i, *vals)
except Exception:
    pass
' >>"${out}"
  sleep 10
done
