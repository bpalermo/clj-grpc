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
  grep '^#NH-STEP' "${log}" | sed 's/^#NH-STEP //' |
    jq -r '"\(.rps) \(.warmup) \(.start) \(.end)"' |
    while read -r rps warm start end; do
      awk -v s="${start}" -v e="${end}" -v rps="${rps}" -v warm="${warm}" '
        $1 >= s && $1 <= e && NF == 5 {
          if (u0 == "") { u0 = $3; t0 = $4; n0 = $5 }
          u1 = $3; t1 = $4; n1 = $5; have = 1
        }
        END {
          tag = (warm == "true") ? " (warmup)" : ""
          if (have && (e - s) > 0)
            printf "%-16s %6.2f cores  %6.2f s throttled  %d periods\n", \
                   rps tag, (u1 - u0) / 1e6 / (e - s), (t1 - t0) / 1e6, n1 - n0
          else
            printf "%-16s   n/a (no samples in window)\n", rps tag
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
