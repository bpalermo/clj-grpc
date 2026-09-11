#!/usr/bin/env bash
# VT, 4 cores, one connection, held at 240k msg/s for 90 s while the java
# process's per-thread CPU is sampled: is one thread (the connection's event
# loop) pinned while the rest idle?
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"; RES="$W/results"
NH=ghcr.io/bpalermo/nighthawk:bc9de452fa39efd6d4ddd00c0b545cde275eddae
BASE=gcr.io/distroless/java21-debian12@sha256:f34fd3e4e2d7a246d764d0614f5e6ffb3a735930723fac4cfc25a72798950262
docker rm -f srv >/dev/null 2>&1 || true
docker run -d --name srv --network host --cpuset-cpus 2,3,4,5 --cpus 4 --memory 1g \
  -e PORT=8080 -e EXECUTOR=virtual -e JAVA_TOOL_OPTIONS=-Dclojure.compiler.direct-linking=true \
  -v "$W/app.jar:/app.jar:ro" "$BASE" -Xmx512m -Dio.netty.leakDetection.level=disabled -jar /app.jar >/dev/null
for i in $(seq 1 60); do curl -sf --max-time 2 localhost:9090/metrics >/dev/null && break; sleep 1; done
pid=$(docker inspect -f '{{.State.Pid}}' srv)
( for i in $(seq 1 20); do sleep 10; echo "--- t=$((i*10))s"; ps -L -o tid,pcpu,comm -p "$pid" --sort=-pcpu | head -8; done ) > "$RES/verify-threads.txt" 2>&1 &
sampler=$!
docker run --rm --network host --cpuset-cpus 6,7,8,9 --cpus 4 --tmpfs /tmp --entrypoint /bin/sh \
  -v "$W/bodies:/bodies:ro" -v "$W/nh:/nh:ro" \
  -e TARGET=127.0.0.1 -e MODE=grpc-stream -e TIER=realistic -e RAMP="240000" -e STEP_SECONDS=120 \
  -e WARMUP_RPS=80000 -e WARMUP_SECONDS=60 -e CONCURRENCY=1 -e IDLE_STRATEGY=spin -e BODY_VIA_FILE=yes \
  -e HTTP1_CONNECTIONS=256 -e HTTP2_CONNECTIONS=8 -e MAX_ACTIVE_REQUESTS=4096 -e MAX_CONCURRENT_STREAMS=512 \
  -e STREAMS=40 -e INFLIGHT=256 -e STREAM_DRAIN=0.5s "$NH" /nh/run.sh > "$RES/verify-threads.log" 2> "$RES/verify-threads.err"
kill $sampler 2>/dev/null; docker rm -f srv >/dev/null 2>&1
echo "verify-threads complete"
