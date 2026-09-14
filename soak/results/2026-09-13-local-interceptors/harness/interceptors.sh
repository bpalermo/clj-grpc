#!/usr/bin/env bash
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"
source "$W/vt-levers-lib.sh"; RES="$W/results10"
C="-e INBOUND_CREDITS=8 -e EXECUTOR=virtual"; Y="-Xmn256m"; D8=6,7,8,9,16,17,18,19
run_u() { # label jar extra-env  (unary, eight connections, eight workers, MCS 4)
  local label="$1" jar="$2" e="$3"; [ -s "$RES/$label.log" ] && return
  docker rm -f srv >/dev/null 2>&1 || true
  docker run -d --name srv --network host --cpuset-cpus 2,3,4,5 --cpus 4 --memory 1g -e PORT=8080 $C $e -e JAVA_TOOL_OPTIONS=-Dclojure.compiler.direct-linking=true -v "$jar:/app.jar:ro" "$J21" -Xmx512m $Y -Dio.netty.leakDetection.level=disabled -jar /app.jar >/dev/null
  for i in $(seq 1 60); do curl -sf --max-time 2 localhost:9090/metrics >/dev/null && break; sleep 1; done
  log "start $label"; echo "#NH-RUN $label start=$(date +%s)" >> "$RES/steps.log"
  docker run --rm --network host --cpuset-cpus $D8 --cpus 8 --tmpfs /tmp --entrypoint /bin/sh -v "$W/bodies:/bodies:ro" -v "$W/nh:/nh:ro" \
    -e TARGET=127.0.0.1 -e MODE=grpc-unary -e TIER=realistic -e RAMP="40000 60000 80000 100000" -e STEP_SECONDS=60 -e WARMUP_RPS=2000 -e WARMUP_SECONDS=90 \
    -e CONCURRENCY=8 -e IDLE_STRATEGY=spin -e BODY_VIA_FILE=yes -e HTTP1_CONNECTIONS=256 -e HTTP2_CONNECTIONS=8 -e MAX_ACTIVE_REQUESTS=4096 -e MAX_CONCURRENT_STREAMS=4 \
    -e STREAMS=40 -e INFLIGHT=256 -e STREAM_DRAIN=0.5s "$NH" /nh/run.sh > "$RES/$label.log.tmp" 2> "$RES/$label.err" || log "driver exited $? on $label"
  mv "$RES/$label.log.tmp" "$RES/$label.log"; echo "#NH-RUN $label end=$(date +%s)" >> "$RES/steps.log"
  docker logs srv 2>&1 | grep -i -E 'exception|error' | head -3 > "$RES/$label.srv" || true
  docker rm -f srv >/dev/null 2>&1 || true
  { echo "### local:${label} (unary, 8 conns, 8 workers, MCS 4)"; "$REPO/soak/collect.sh" grpc-unary < "$RES/$label.log"; echo; } >> "$RES/tables.md"
  log "done $label"
}
for shape in "s1 512 1 ''" "s8 5 4 '-e WORKER_THREADS=2'"; do
  eval "set -- $shape"; sh=$1; mcs=$2; conc=$3; extra=$4
  run_one "A0-$sh" "$J21" "$W/base.jar" "$C $extra" "$Y" $mcs $conc
  run_one "A1-$sh" "$J21" "$W/int.jar"  "$C $extra" "$Y" $mcs $conc
  run_one "B-$sh"  "$J21" "$W/int.jar"  "$C $extra -e INTERCEPTOR=passthrough" "$Y" $mcs $conc
  run_one "C-$sh"  "$J21" "$W/int.jar"  "$C $extra -e INTERCEPTOR=headers" "$Y" $mcs $conc
done
run_u A0-u8 "$W/base.jar" ""
run_u A1-u8 "$W/int.jar" ""
run_u B-u8  "$W/int.jar" "-e INTERCEPTOR=passthrough"
run_u C-u8  "$W/int.jar" "-e INTERCEPTOR=headers"
log "interceptors complete"
