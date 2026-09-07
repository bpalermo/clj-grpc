#!/bin/sh
# The stepped open-loop ramp, one Nighthawk invocation per step.
#
# This is the ONE place a mode (http1 | http2 | grpc-unary | grpc-stream)
# turns into Nighthawk flags, so the gRPC and streaming flags — which come
# from the bpalermo/nighthawk fork, not upstream — are a one-file edit here.
#
# Output contract (stdout is the artifact; soak/collect.sh parses it):
#   #NH-STEP {"rps":R,"warmup":true|false,"start":EPOCH,"end":EPOCH,
#             "metrics_before":"<base64 of GET /metrics>","metrics_after":"<base64>"}
#   <Nighthawk JSON, one document>
#   #NH-END
# Nighthawk writes only its JSON to stdout and logs to stderr, so the JSON
# between the markers is parseable as-is. Everything this script says for a
# human goes to stderr too.
set -eu

: "${TARGET:?}" "${MODE:?}" "${TIER:?}" "${RAMP:?}" "${STEP_SECONDS:?}"
: "${WARMUP_RPS:=200}" "${WARMUP_SECONDS:=120}" "${CONCURRENCY:=2}"
: "${HTTP1_CONNECTIONS:=256}" "${HTTP2_CONNECTIONS:=8}"
: "${MAX_ACTIVE_REQUESTS:=4096}" "${MAX_CONCURRENT_STREAMS:=512}"
: "${STREAMS:=20}" "${INFLIGHT:=256}" "${STREAM_DRAIN:=0.5s}"

METRICS_URL="http://${TARGET}:9090/metrics"
BASE="http://${TARGET}:8080"

log() { echo "run.sh: $*" >&2; }

# The arm's own cgroup counters, base64 so the JSON header stays one line.
# Failure is tolerated: a missing snapshot costs a CPU column, not the run.
#
# Patient, because past the knee the arm is CPU-throttled and its metrics
# thread waits behind thousands of queued requests: with a 5 s budget the h2c
# tiny run lost every snapshot from 1200 rps up. Three tries of 30 s each is
# still short next to a 110 s step, and the snapshot is taken between steps.
snapshot() {
  local out="" try
  for try in 1 2 3; do
    out=$(curl -sf --max-time 30 "${METRICS_URL}" 2>/dev/null | base64 | tr -d '\n' || true)
    [ -n "${out}" ] && break
    log "metrics snapshot attempt ${try} failed"
  done
  printf '%s' "${out}"
}

# Nighthawk's --rps, --connections and --max-active-requests are PER WORKER
# (its log says so: "Global targets: 800 calls per second (Per-worker
# targets: 400)"), and --concurrency is the worker count. Every rate and
# budget in this file is the AGGREGATE the values.yaml states; this is where
# it is divided. The one exception is --grpc-stream, whose --rps the fork
# defines as aggregate — streams are divided across workers there instead.
per_worker() { echo $(( $1 / CONCURRENCY )); }

# Flags common to every mode. Open loop, no client-side queueing, and no
# default failure predicates: the ramp is MEANT to exceed capacity, and the
# counters (pool_overflow, grpc_error, stream_deferred) are how saturation is
# read, not a reason to stop.
common() {
  local rate
  # --grpc-stream takes the AGGREGATE rate (fork semantics); everything else per worker.
  if [ "${MODE}" = "grpc-stream" ]; then rate="$1"; else rate="$(per_worker "$1")"; fi
  echo "--open-loop --rps ${rate} --duration $2 --concurrency ${CONCURRENCY}" \
       "--max-pending-requests 0 --no-default-failure-predicates" \
       "--sequencer-idle-strategy ${IDLE_STRATEGY:-spin} --output-format json"
}

# The REST body. --request-body-file arrives with the fork's P1; until then
# the same bytes go through Nighthawk's in-line request-source plugin, which
# also sets content-type: application/json. Both paths send the file verbatim.
rest_body() {
  if [ "${BODY_VIA_FILE:-no}" = "yes" ]; then
    echo "--request-header 'content-type: application/json' --request-body-file /bodies/${TIER}.json"
  else
    body="$(sed 's/\\/\\\\/g; s/"/\\"/g' "/bodies/${TIER}.json" | tr -d '\n')"
    echo "--request-source-plugin-config '{name: \"nighthawk.in-line-options-list-request-source-plugin\", typed_config: {\"@type\": \"type.googleapis.com/nighthawk.request_source.InLineOptionsListRequestSourceConfig\", options_list: {options: [{request_method: \"POST\", json_body: \"${body}\"}]}}}'"
  fi
}

# Per-mode flags and URI. Bodies are the chart's generated files: JSON for
# the REST arms, the raw serialized HelloRequest for the gRPC arms — the fork
# adds the five-byte gRPC frame itself.
mode_args() {
  case "${MODE}" in
    http1)
      echo "--protocol http1 --connections $(per_worker "${HTTP1_CONNECTIONS}") --prefetch-connections" \
           "--request-method POST $(rest_body) ${BASE}/hello" ;;
    http2)
      echo "--protocol http2 --connections $(per_worker "${HTTP2_CONNECTIONS}")" \
           "--max-active-requests $(per_worker "${MAX_ACTIVE_REQUESTS}") --max-concurrent-streams ${MAX_CONCURRENT_STREAMS}" \
           "--request-method POST $(rest_body) ${BASE}/hello" ;;
    grpc-unary)
      echo "--grpc --connections $(per_worker "${HTTP2_CONNECTIONS}")" \
           "--max-active-requests $(per_worker "${MAX_ACTIVE_REQUESTS}") --max-concurrent-streams ${MAX_CONCURRENT_STREAMS}" \
           "--request-body-file /bodies/${TIER}.pb ${BASE}/acme.greeter.Greeter/SayHello" ;;
    grpc-stream)
      # --rps is aggregate here and already emitted by common(); --streams is the
      # total across workers (must be a multiple of --concurrency).
      echo "--grpc-stream --streams ${STREAMS} --max-inflight-per-stream ${INFLIGHT}" \
           "--max-active-requests $(per_worker "${MAX_ACTIVE_REQUESTS}")" \
           "--stream-drain-duration ${STREAM_DRAIN}" \
           "--request-body-file /bodies/${TIER}.pb ${BASE}/acme.greeter.Greeter/Chat" ;;
    *)
      log "unknown MODE '${MODE}' (http1|http2|grpc-unary|grpc-stream)"; exit 2 ;;
  esac
}

step() {
  rps="$1"; seconds="$2"; warmup="$3"
  before="$(snapshot)"
  start="$(date +%s)"
  # eval, because mode_args carries a quoted header value.
  eval "set -- $(common "${rps}" "${seconds}") $(mode_args)"
  log "step rps=${rps} duration=${seconds}s warmup=${warmup} mode=${MODE} tier=${TIER}"
  out="$(nighthawk_client "$@")" || log "nighthawk_client exited $? at rps=${rps} (counters tell the story; continuing)"
  end="$(date +%s)"
  after="$(snapshot)"
  printf '#NH-STEP {"rps":%s,"warmup":%s,"start":%s,"end":%s,"metrics_before":"%s","metrics_after":"%s"}\n' \
    "${rps}" "${warmup}" "${start}" "${end}" "${before}" "${after}"
  printf '%s\n' "${out}"
  echo "#NH-END"
}

log "target=${TARGET} mode=${MODE} tier=${TIER} ramp='${RAMP}' step=${STEP_SECONDS}s"
log "$(curl -sf --max-time 5 "${METRICS_URL}" | head -c 400 | tr '\n' ' ' || echo 'metrics endpoint unreachable')"

# JIT warmup on a fresh pod: tagged so the collector excludes it.
step "${WARMUP_RPS}" "${WARMUP_SECONDS}" true
for rps in ${RAMP}; do
  step "${rps}" "${STEP_SECONDS}" false
done
log "done"
