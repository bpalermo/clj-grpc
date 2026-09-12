#!/usr/bin/env bash
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"
until grep -q 'slot pair 2 complete' "$W/results5/runner.log" 2>/dev/null; do sleep 30; done
source "$W/vt-levers-lib.sh"; RES="$W/results5"
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
    -e STREAMS=40 -e INFLIGHT=256 -e STREAM_DRAIN=0.5s "$NH" /nh/run.sh > "$RES/$label.log.tmp" 2> "$RES/$label.err" || log "driver exited $? on $label"
  mv "$RES/$label.log.tmp" "$RES/$label.log"; echo "#NH-RUN $label end=$(date +%s)" >> "$RES/steps.log"
  docker rm -f srv >/dev/null 2>&1 || true
  { echo "### local:${label} (image=${image##*/}; jar=$(basename "$jar"); env='${eenv}'; jvm='${ejvm}'; mcs=${mcs}; workers=${conc}; ramp=${ramp}; 4 cores)"
    "$REPO/soak/collect.sh" grpc-stream < "$RES/$label.log"; echo; } >> "$RES/tables.md"
  log "done $label"
}

# :direct eight connections past the knee (both jars delivered all 400k on the standard ramp)
C="-e INBOUND_CREDITS=8 -e EXECUTOR=direct"; Y="-Xmn256m"
run_one_ramp main3-direct-8conn "$J21" "$W/main.jar" "$C" "$Y" 5 4 "400000 480000 560000 640000"
run_one_ramp slot3-direct-8conn "$J21" "$W/slot.jar" "$C" "$Y" 5 4 "400000 480000 560000 640000"
log "slot pair 3 complete"
