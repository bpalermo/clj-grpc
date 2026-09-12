#!/usr/bin/env bash
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"
until grep -q "batching complete" "$W/results7/runner.log" 2>/dev/null; do sleep 15; done
source "$W/vt-levers-lib.sh"; RES="$W/results7"
NH=ghcr.io/bpalermo/nighthawk:0649c7b0f943e54ee2c5d537c1b78c407c88da9b
run_one_ramp() { # same as run_one but with a custom ramp in $8
  local label="$1" image="$2" jar="$3" eenv="$4" ejvm="$5" mcs="$6" conc="$7" ramp="$8"
  [ -s "$RES/$label.log" ] && { log "skip $label"; return; }
  docker rm -f srv >/dev/null 2>&1 || true
  # shellcheck disable=SC2086
  docker run -d --name srv --network host --cpuset-cpus 2,3,4,5 --cpus 4 --memory 1g \
    -e PORT=8080 -e EXECUTOR=virtual -e JAVA_TOOL_OPTIONS=-Dclojure.compiler.direct-linking=true $eenv \
    -v "$jar:/app.jar:ro" "$image" -Xmx512m -Dio.netty.leakDetection.level=disabled $ejvm -jar /app.jar >/dev/null
  for i in $(seq 1 60); do curl -sf --max-time 2 localhost:9090/metrics >/dev/null && break; sleep 1; done
  log "start $label"; echo "#NH-RUN $label start=$(date +%s)" >> "$RES/steps.log"
  docker run --rm --network host --cpuset-cpus 6,7,8,9 --cpus 4 --tmpfs /tmp --entrypoint /bin/sh \
    -v "$W/bodies:/bodies:ro" -v "$W/nh:/nh:ro" \
    -e TARGET=127.0.0.1 -e MODE=grpc-stream -e TIER=realistic -e RAMP="$ramp" -e STEP_SECONDS=60 \
    -e WARMUP_RPS=2000 -e WARMUP_SECONDS=90 -e CONCURRENCY="$conc" -e IDLE_STRATEGY=spin -e BODY_VIA_FILE=yes \
    -e HTTP1_CONNECTIONS=256 -e HTTP2_CONNECTIONS=8 -e MAX_ACTIVE_REQUESTS=4096 -e MAX_CONCURRENT_STREAMS="$mcs" \
    -e STREAMS=40 -e INFLIGHT=256 -e STREAM_DRAIN=0.5s -e EXTRA_NH_ARGS="${EXTRA_NH_ARGS:-}" "$NH" /nh/run.sh > "$RES/$label.log.tmp" 2> "$RES/$label.err" || log "driver exited $? on $label"
  mv "$RES/$label.log.tmp" "$RES/$label.log"; echo "#NH-RUN $label end=$(date +%s)" >> "$RES/steps.log"
  docker rm -f srv >/dev/null 2>&1 || true
  { echo "### local:${label} (image=${image##*/}; jar=$(basename "$jar"); env='${eenv}'; jvm='${ejvm}'; mcs=${mcs}; workers=${conc}; ramp=${ramp}; 4 cores)"
    "$REPO/soak/collect.sh" grpc-stream < "$RES/$label.log"; echo; } >> "$RES/tables.md"
  log "done $label"
}

C="-e INBOUND_CREDITS=8 -e REPLY=full"; Y="-Xmn256m"; RAMP="160000 240000 320000 400000"
export EXTRA_NH_ARGS="--stream-batch-messages 32 --stream-batch-flush-interval 0.005s"
(setsid nohup "$W/usersys-at-top7.sh" "batch32-5ms-1conn" >/dev/null 2>&1 &)
run_one_ramp "batch32-5ms-1conn" "$J21" "$W/reply.jar" "$C" "$Y" 512 1 "$RAMP"
log "batching 5ms complete"
