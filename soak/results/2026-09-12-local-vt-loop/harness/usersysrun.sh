#!/usr/bin/env bash
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"
true
source "$W/vt-levers-lib.sh"; RES="$W/results6"
NH=ghcr.io/bpalermo/nighthawk:bc9de452fa39efd6d4ddd00c0b545cde275eddae
docker rm -f srv >/dev/null 2>&1 || true
docker run -d --name srv --network host --cpuset-cpus 2,3,4,5 --cpus 4 --memory 1g \
  -e PORT=8080 -e EXECUTOR=virtual -e INBOUND_CREDITS=8 -e REPLY=full -e JAVA_TOOL_OPTIONS=-Dclojure.compiler.direct-linking=true \
  -v "$W/reply.jar:/app.jar:ro" "$J21" -Xmx512m -Xmn256m -Dio.netty.leakDetection.level=disabled \
  -jar /app.jar >/dev/null
for i in $(seq 1 60); do curl -sf --max-time 2 localhost:9090/metrics >/dev/null && break; sleep 1; done
log "start usersys-1conn"; echo "#NH-RUN usersys-1conn start=$(date +%s)" >> "$RES/steps.log"
docker run --rm --network host --cpuset-cpus 6,7,8,9 --cpus 4 --tmpfs /tmp --entrypoint /bin/sh \
  -v "$W/bodies:/bodies:ro" -v "$W/nh:/nh:ro" \
  -e TARGET=127.0.0.1 -e MODE=grpc-stream -e TIER=realistic -e RAMP="160000 240000 320000 400000" -e STEP_SECONDS=60 \
  -e WARMUP_RPS=2000 -e WARMUP_SECONDS=90 -e CONCURRENCY=1 -e IDLE_STRATEGY=spin -e BODY_VIA_FILE=yes \
  -e HTTP1_CONNECTIONS=256 -e HTTP2_CONNECTIONS=8 -e MAX_ACTIVE_REQUESTS=4096 -e MAX_CONCURRENT_STREAMS=512 \
  -e STREAMS=40 -e INFLIGHT=256 -e STREAM_DRAIN=0.5s "$NH" /nh/run.sh > "$RES/usersys-1conn.log" 2> "$RES/usersys-1conn.err" || log "driver exited $? on usersys-1conn"
echo "#NH-RUN usersys-1conn end=$(date +%s)" >> "$RES/steps.log"
docker stop -t 30 srv >/dev/null 2>&1; docker rm -f srv >/dev/null 2>&1 || true
ls -la "$W/jfr/" >&2
log "jfr complete"
