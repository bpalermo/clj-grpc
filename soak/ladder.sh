#!/usr/bin/env bash
# Run the switch ladder: one Nighthawk Job per (arm, mode, tier), in sequence,
# each against a freshly restarted arm (run spec: arm:mode:tier[:ramp[:streams]]), with the sibling arm on that node
# (rest-h1/rest-h2c, grpc-native/grpc-jvm-interop) scaled to zero so nothing
# else on that node is warm. Logs land in RESULTS_DIR as
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
chart_version="${CHART_VERSION:-}"
profiling="${PROFILING:-false}"
# A fresh JVM arm under a 1-CPU quota spends minutes compiling: the first smoke
# gave 30 s and measured p50 105 ms at 200 rps; the same pod warm measured 2.6 ms.
# Five minutes at the warmup rate is cheap insurance against reading JIT as protocol.
warmup_seconds="${WARMUP_SECONDS:-300}"

if [ "$#" -gt 0 ]; then
  runs=("$@")
else
  runs=(rest-h1:http1:tiny rest-h2c:http2:tiny grpc-jvm:grpc-unary:tiny grpc-jvm:grpc-stream:tiny
        rest-h1:http1:realistic rest-h2c:http2:realistic grpc-jvm:grpc-unary:realistic grpc-jvm:grpc-stream:realistic)
fi
mkdir -p "${results_dir}"

log() { echo "ladder: $(date -u +%H:%M:%S) $*" >&2; }

# Extra --set pairs applied to EVERY upgrade of every run (an override that is
# not repeated on the Job-start upgrade is silently undone by it, which is how
# the first rest-h2c run measured an empty Service). Space-separated, e.g.
#   LADDER_EXTRA_SET="arms.grpc-jvm.extraEnv.EXECUTOR=virtual"
read -ra extra_set <<<"${LADDER_EXTRA_SET:-}"
extra_flags=()
for kv in "${extra_set[@]}"; do extra_flags+=(--set-string "${kv}"); done

# The chart does not pin the native image (GraalVM is not reproducible, and its
# numbers only compare within one digest), so a native run says which one it
# means. Carried on EVERY upgrade for the same reason the pairing is: the
# Job-start upgrade re-applies the chart and would drop it.
if [ -n "${NATIVE_IMAGE:-}" ]; then
  extra_flags+=(--set-string "arms.grpc-native.image=${NATIVE_IMAGE}")
fi

upgrade() { # $@ = --set key=value pairs
  if [ "${chart}" = "bazel" ]; then
    bazel run //charts:soak.upgrade -- --namespace "${ns}" --set "profiling.enabled=${profiling}" "${extra_flags[@]}" "$@"
  else
    version_flag=()
    [ -n "${chart_version}" ] && version_flag=(--version "${chart_version}")
    helm --kube-context "${context}" upgrade clj-grpc-soak "${chart}" "${version_flag[@]}" --namespace "${ns}" \
      --set "profiling.enabled=${profiling}" --set "loadJob.warmup.seconds=${warmup_seconds}" "${extra_flags[@]}" "$@"
  fi
}

# The REST pairing is expressed to Helm, not to kubectl: every `helm upgrade`
# re-applies the chart's replica counts, so a `kubectl scale` is undone by the
# very upgrade that starts the Job. The first ladder measured rest-h2c against a
# Service with no endpoints because of exactly that (0 delivered on every step).
pairing=()

wait_ready() { # $1 = deployment
  kubectl --context "${context}" -n "${ns}" rollout status deployment "$1" --timeout=10m >/dev/null
}

realistic_ramp="${REALISTIC_RAMP:-100 200 300 400 500 600 800 1000 1200 1400 1600}"

# Fail before deploying anything rather than measuring an arm that is not
# there: an imageless arm renders no Service, and a Job pointed at a missing
# Service reports zeros on every step instead of failing.
for run in "${runs[@]}"; do
  if [ "${run%%:*}" = "grpc-native" ] && [ -z "${NATIVE_IMAGE:-}" ]; then
    log "run '${run}' targets grpc-native, which this chart does not pin."
    log "Set NATIVE_IMAGE=ghcr.io/bpalermo/clj-grpc/soak-grpc-native@sha256:<digest> and re-run."
    exit 2
  fi
done

for run in "${runs[@]}"; do
  IFS=: read -r arm mode tier ramp streams <<<"${run}"
  if [ -z "${ramp:-}" ] && [ "${tier}" = "realistic" ]; then ramp="${realistic_ramp}"; fi
  run_id="-$(date -u +%m%d%H%M)"
  job="nh-${arm}-${mode}-${tier}${run_id}"
  log "=== ${run} → ${job} ==="

  # The two REST arms share a node and an image; only one may be up. The
  # pairing rides on every upgrade of this run (Recreate does the swap).
  case "${arm}" in
    rest-h1)  pairing=(--set arms.rest-h1.replicas=1 --set arms.rest-h2c.replicas=0) ;;
    rest-h2c) pairing=(--set arms.rest-h1.replicas=0 --set arms.rest-h2c.replicas=1) ;;
    # grpc-jvm-interop shares worker-02 with grpc-native for the same reason
    # the REST arms share worker-04: one Guaranteed 1-CPU arm per node.
    grpc-jvm-interop) pairing=(--set arms.grpc-native.replicas=0 --set arms.grpc-jvm-interop.replicas=1) ;;
    grpc-native)      pairing=(--set arms.grpc-jvm-interop.replicas=0 --set arms.grpc-native.replicas=1) ;;
    *)        pairing=() ;;
  esac
  upgrade --set loadJob.enabled=false "${pairing[@]}" >/dev/null
  # A fresh pod for every run: the warmup step is tagged, and a JVM carrying
  # another run's JIT state would make it a lie.
  kubectl --context "${context}" -n "${ns}" rollout restart deployment "${arm}" >/dev/null
  wait_ready "${arm}"
  log "${arm} ready on $(kubectl --context "${context}" -n "${ns}" get pod -l app="${arm}" -o jsonpath='{.items[0].spec.nodeName}')"

  # --set-string, not --set: a run id like -09062023 or a one-step ramp like
  # 200 is parsed by --set as a NUMBER, and the Job name then renders as
  # %!s(int64=-9062023), which Kubernetes refuses. Learned by the first ladder.
  ramp_set=()
  [ -n "${ramp:-}" ] && ramp_set=(--set-string "loadJob.ramp=${ramp}")
  # Fifth field: stream count for grpc-stream runs (August's two shapes are 20 and 40).
  [ -n "${streams:-}" ] && ramp_set+=(--set-string "loadJob.stream.streams=${streams}")
  upgrade --set loadJob.enabled=true "${pairing[@]}" \
          --set-string "loadJob.target=${arm}" --set-string "loadJob.mode=${mode}" --set-string "loadJob.tier=${tier}" \
          --set-string "loadJob.runId=${run_id}" "${ramp_set[@]}" >/dev/null
  # What this run measured, recorded where it survives. `log` goes to stderr
  # and nothing keeps it; the artifacts are the saved job log and tables.md,
  # so the digest goes in both. Only the native arm needs it: every other
  # arm's image is pinned by the chart, and the chart version is in the run's
  # own metadata.
  provenance=""
  [ "${arm}" = "grpc-native" ] && provenance="; native image: ${NATIVE_IMAGE:-unset}"
  log "job ${job} started${provenance}; waiting"
  if ! kubectl --context "${context}" -n "${ns}" wait --for=condition=complete "job/${job}" --timeout=90m; then
    log "job ${job} did not complete cleanly; saving what it logged"
  fi
  kubectl --context "${context}" -n "${ns}" logs "job/${job}" > "${results_dir}/${job}.log"
  # A missing pod is itself the finding (the arm was not up during the run);
  # it must not abort the ladder before the log is collected.
  restarts=$(kubectl --context "${context}" -n "${ns}" get pod -l app="${arm}" -o jsonpath='{.items[0].status.containerStatuses[0].restartCount}' 2>/dev/null || echo "no-pod")
  log "saved ${results_dir}/${job}.log; ${arm} restarts during run: ${restarts:-?}"
  [ "${restarts:-0}" = "0" ] || log "WARNING: ${arm} restarted — this run is void"

  upgrade --set loadJob.enabled=false "${pairing[@]}" >/dev/null
  echo "### ${run} (${job}; restarts=${restarts:-?}${provenance})" >> "${results_dir}/tables.md"
  soak/collect.sh "${mode}" < "${results_dir}/${job}.log" >> "${results_dir}/tables.md"
  # After collect.sh has read the log, not before: the collector parses this
  # file and should not have to know about a trailer.
  [ -n "${provenance}" ] && echo "#PROVENANCE${provenance}" >> "${results_dir}/${job}.log"
  echo >> "${results_dir}/tables.md"
done
log "ladder complete: ${results_dir}/tables.md"
