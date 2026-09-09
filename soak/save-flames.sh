#!/usr/bin/env bash
# Bank a Pyroscope flamebearer for one step of one run, so an attribution read
# survives the server's retention. Pyroscope keeps a rolling window; these
# windows are the evidence behind published numbers and outlive it.
#
#   soak/save-flames.sh OUTDIR LOG STEP_INDEX LABEL SERVICE...
#
# STEP_INDEX is 1-based over the run's #NH-STEP markers (the warmup is 1).
# SERVICE is a Pyroscope service_name: the arm's own `<arm>-java` for the Java
# agent's symbolized profile, `unknown_service:nighthawk_client` for the
# driver as the cluster's eBPF profiler sees it. Pass both to bank both sides
# of a step — a ceiling that turns out to be the driver's is only diagnosable
# if the driver was banked too.
#
# soak/pyro.py reads the saved file directly: `soak/pyro.py <file.json.gz>`.
set -uo pipefail
out="${1:?usage: save-flames.sh OUTDIR LOG STEP LABEL SERVICE...}"
log="${2:?}"; step="${3:?}"; label="${4:?}"; shift 4
mkdir -p "${out}"
reader=cat; case "${log}" in *.gz) reader=zcat ;; esac
window=$(${reader} "${log}" | grep '^#NH-STEP' | sed -n "${step}p" | sed 's/^#NH-STEP //' |
         jq -r '"\(.start) \(.end) \(.rps)"')
[ -z "${window}" ] && { echo "save-flames: no step ${step} in ${log}" >&2; exit 2; }
from=${window%% *}; rest=${window#* }; until=${rest%% *}; rps=${rest##* }
for svc in "$@"; do
  q=$(python3 -c 'import urllib.parse,sys; print(urllib.parse.quote("process_cpu:cpu:nanoseconds:cpu:nanoseconds{service_name=\"%s\"}" % sys.argv[1]))' "${svc}")
  safe=$(printf '%s' "${svc}" | tr -c 'A-Za-z0-9._-' '-')
  f="${out}/${label}.rps${rps}.${safe}.${from}-${until}.json.gz"
  if kubectl --context "${KUBE_CONTEXT:-talos-main}" get --raw \
      "/api/v1/namespaces/o11y/services/pyroscope:4040/proxy/pyroscope/render?query=${q}&from=${from}&until=${until}&format=json" \
      | gzip -9 > "${f}" && [ -s "${f}" ]; then
    echo "saved ${label} ${svc} @${rps} ($(stat -c %s "${f}") bytes)"
  else
    echo "save-flames: nothing returned for ${svc} ${from}-${until}" >&2; rm -f "${f}"
  fi
done
