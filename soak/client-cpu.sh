#!/usr/bin/env bash
# Sample the load Job's OWN CPU while a ladder runs, so that a client which ran
# out of CPU cannot be read as a server which ran out of capacity.
#
#   soak/client-cpu.sh OUT.tsv                 # sampler; run detached alongside ladder.sh
#   soak/client-cpu.sh --report JOB.log OUT.tsv  # align the samples to each step
#
# Why not `kubectl top`: --sequencer-idle-strategy spin busy-waits, so the Job's
# CPU sits near a core whether it is idle or desperate, and a point-in-time
# reading says nothing. What separates the two is throttling. The Job is
# Guaranteed (requests == limits), so a quota exists and throttled_usec rises
# only when the client genuinely wanted more CPU than it was given. Both come
# from the pod's own cgroup, which is exact, cumulative and free of the
# metrics-server sampling window.
#
# Sample format, one line per sample:
#   <epoch> <pod> <usage_usec> <throttled_usec> <nr_throttled>
set -uo pipefail
ctx="${KUBE_CONTEXT:-talos-main}"; ns="${NAMESPACE:-clj-grpc-soak}"

if [ "${1:-}" = "--report" ]; then
  log="${2:?usage: client-cpu.sh --report JOB.log SAMPLES.tsv}"
  tsv="${3:?usage: client-cpu.sh --report JOB.log SAMPLES.tsv}"
  # Older sample files carry 4 fields (no throttling); NF == 5 skips them
  # rather than silently subtracting a usage figure from a throttle figure.
  #
  # Three ways this used to under-read, all in the reassuring direction:
  #   - the first sample lands up to one interval AFTER the window opens, so
  #     dividing by the requested window understates the rate by ~9% on a 110 s
  #     step. Divide by the span actually sampled.
  #   - one sample in a window gives a zero difference, which printed as a
  #     confident "0.00 cores" — an idle-looking driver, the exact reading this
  #     script exists to disprove. Two distinct timestamps or nothing.
  #   - counters are per cgroup, so a lingering pod from the previous run in the
  #     same window made the difference meaningless. Use the pod that dominates
  #     the window and say so when there was more than one.
  grep '^#NH-STEP' "${log}" | sed 's/^#NH-STEP //' |
    jq -r '"\(.rps) \(.warmup) \(.start) \(.end)"' |
    while read -r rps warm start end; do
      awk -v s="${start}" -v e="${end}" -v rps="${rps}" -v warm="${warm}" '
        $1 >= s && $1 <= e && NF == 5 { seen[$2]++; n++; ts[n] = $1; pod[n] = $2; u[n] = $3; t[n] = $4; c[n] = $5 }
        END {
          tag = (warm == "true") ? " (warmup)" : ""
          best = ""; for (p in seen) if (best == "" || seen[p] > seen[best]) best = p
          pods = 0; for (p in seen) pods++
          first = 0; last = 0
          for (i = 1; i <= n; i++) {
            if (pod[i] != best) continue
            if (first == 0) first = i
            last = i
          }
          if (first == 0 || ts[last] == ts[first]) {
            printf "%-16s   n/a (%d sample(s) in window)\n", rps tag, (first == 0 ? 0 : last - first + 1)
            next_line = 1
          } else {
            span = ts[last] - ts[first]
            warn = (pods > 1) ? sprintf("  [%d pods in window; used %s]", pods, best) : ""
            printf "%-16s %6.2f cores  %6.2f s throttled  %d periods  (%ds sampled)%s\n", \
                   rps tag, (u[last] - u[first]) / 1e6 / span, (t[last] - t[first]) / 1e6, \
                   c[last] - c[first], span, warn
          }
        }' "${tsv}"
    done
  exit 0
fi

out="${1:?usage: client-cpu.sh OUT.tsv}"
while true; do
  pod=$(kubectl --context "${ctx}" -n "${ns}" get pods -l job-name \
        --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
  if [ -n "${pod:-}" ]; then
    line=$(kubectl --context "${ctx}" -n "${ns}" exec "${pod}" -- sh -c \
      'awk "/usage_usec/{u=\$2} /throttled_usec/{t=\$2} /nr_throttled/{n=\$2} END{print u, t, n}" /sys/fs/cgroup/cpu.stat' 2>/dev/null)
    [ -n "${line}" ] && echo "$(date -u +%s) ${pod} ${line}" >>"${out}"
  fi
  sleep 10
done
