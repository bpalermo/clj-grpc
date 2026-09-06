#!/usr/bin/env bash
# Run the switch ladder: one Nighthawk Job per (arm, mode, tier), in sequence,
# each against a freshly restarted arm, with the sibling REST arm scaled to
# zero so nothing else on that node is warm. Logs land in RESULTS_DIR as
# <runId>.log; soak/collect.sh turns each into a table.
#
#   soak/ladder.sh [RESULTS_DIR] [RUN...]
#   RUN is arm:mode:tier[:ramp], e.g. rest-h2c:http2:realistic or
#   rest-h1:http1:realistic:"100 200 300 400 600 800 1000 1200 1600". The ramp
#   defaults to the chart's (200→2400) for tiny and to a lower one for the
#   realistic tier, whose knees come earlier. With no RUNs, the whole ladder
#   runs: R1..R8 = {rest-h1:http1, rest-h2c:http2, grpc-jvm:grpc-unary,
#   grpc-jvm:grpc-stream} × {tiny, realistic}.
#
# Environment: KUBE_CONTEXT (default talos-main), NAMESPACE (clj-grpc-soak),
# CHART — how to upgrade: "bazel" (default; bazel run //charts:soak.upgrade,
# arm64 host only, it pushes what it builds) or an OCI ref for helm upgrade.
# PROFILING=true adds profiling.enabled=true to every upgrade.
#
# Spelled out, no command-in-a-variable: this is the second harness here
# whose sequence monitor once lied because of a shell quirk.
set -euo pipefail

results_dir="${1:-soak/results/$(date -u +%Y-%m-%d)}"; shift || true
context="${KUBE_CONTEXT:-talos-main}"
ns="${NAMESPACE:-clj-grpc-soak}"
chart="${CHART:-bazel}"
profiling="${PROFILING:-false}"

if [ "$#" -gt 0 ]; then
  runs=("$@")
else
  runs=(rest-h1:http1:tiny rest-h2c:http2:tiny grpc-jvm:grpc-unary:tiny grpc-jvm:grpc-stream:tiny
        rest-h1:http1:realistic rest-h2c:http2:realistic grpc-jvm:grpc-unary:realistic grpc-jvm:grpc-stream:realistic)
fi
mkdir -p "${results_dir}"

log() { echo "ladder: $(date -u +%H:%M:%S) $*" >&2; }

upgrade() { # $@ = --set key=value pairs
  if [ "${chart}" = "bazel" ]; then
    bazel run //charts:soak.upgrade -- --namespace "${ns}" --set "profiling.enabled=${profiling}" "$@"
  else
    helm --kube-context "${context}" upgrade clj-grpc-soak "${chart}" --namespace "${ns}" --set "profiling.enabled=${profiling}" "$@"
  fi
}

scale() { # $1 = deployment, $2 = replicas
  kubectl --context "${context}" -n "${ns}" scale deployment "$1" --replicas="$2" >/dev/null
}

wait_ready() { # $1 = deployment
  kubectl --context "${context}" -n "${ns}" rollout status deployment "$1" --timeout=10m >/dev/null
}

realistic_ramp="${REALISTIC_RAMP:-100 200 300 400 500 600 800 1000 1200 1400 1600}"

for run in "${runs[@]}"; do
  IFS=: read -r arm mode tier ramp <<<"${run}"
  if [ -z "${ramp:-}" ] && [ "${tier}" = "realistic" ]; then ramp="${realistic_ramp}"; fi
  run_id="-$(date -u +%m%d%H%M)"
  job="nh-${arm}-${mode}-${tier}${run_id}"
  log "=== ${run} → ${job} ==="

  # The two REST arms share a node and an image; only one may be up.
  case "${arm}" in
    rest-h1)  scale rest-h2c 0; scale rest-h1 1 ;;
    rest-h2c) scale rest-h1 0;  scale rest-h2c 1 ;;
  esac
  # A fresh pod for every run: the warmup step is tagged, and a JVM carrying
  # another run's JIT state would make it a lie.
  kubectl --context "${context}" -n "${ns}" rollout restart deployment "${arm}" >/dev/null
  wait_ready "${arm}"
  log "${arm} ready on $(kubectl --context "${context}" -n "${ns}" get pod -l app="${arm}" -o jsonpath='{.items[0].spec.nodeName}')"

  ramp_set=()
  [ -n "${ramp:-}" ] && ramp_set=(--set "loadJob.ramp=${ramp}")
  upgrade --set loadJob.enabled=true \
          --set "loadJob.target=${arm}" --set "loadJob.mode=${mode}" --set "loadJob.tier=${tier}" \
          --set "loadJob.runId=${run_id}" "${ramp_set[@]}" >/dev/null
  log "job ${job} started; waiting"
  if ! kubectl --context "${context}" -n "${ns}" wait --for=condition=complete "job/${job}" --timeout=90m; then
    log "job ${job} did not complete cleanly; saving what it logged"
  fi
  kubectl --context "${context}" -n "${ns}" logs "job/${job}" > "${results_dir}/${job}.log"
  restarts=$(kubectl --context "${context}" -n "${ns}" get pod -l app="${arm}" -o jsonpath='{.items[0].status.containerStatuses[0].restartCount}')
  log "saved ${results_dir}/${job}.log; ${arm} restarts during run: ${restarts:-?}"
  [ "${restarts:-0}" = "0" ] || log "WARNING: ${arm} restarted — this run is void"

  upgrade --set loadJob.enabled=false >/dev/null
  echo "### ${run} (${job}; restarts=${restarts:-?})" >> "${results_dir}/tables.md"
  soak/collect.sh "${mode}" < "${results_dir}/${job}.log" >> "${results_dir}/tables.md"
  echo >> "${results_dir}/tables.md"
done
log "ladder complete: ${results_dir}/tables.md"
