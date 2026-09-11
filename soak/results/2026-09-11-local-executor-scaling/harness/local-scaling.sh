#!/usr/bin/env bash
# Executor scaling with cores, on a local x86 host: server and driver in
# containers pinned to disjoint physical cores, the chart's run.sh and
# soak/collect.sh reused verbatim. Output: <results>/<label>.log + tables.md.
set -euo pipefail
W="$(cd "$(dirname "$0")" && pwd)"  # staging dir: app.jar, bodies/, nh/run.sh, results/
REPO=/home/palermo/code/bpalermo/clj-grpc
RES="$W/results"
NH=ghcr.io/bpalermo/nighthawk:bc9de452fa39efd6d4ddd00c0b545cde275eddae
BASE=gcr.io/distroless/java21-debian12@sha256:f34fd3e4e2d7a246d764d0614f5e6ffb3a735930723fac4cfc25a72798950262
DRIVER_CPUS=6,7,8,9
log() { echo "$(date +%H:%M:%S) $*" >&2; }

cpuset_for() { case "$1" in 1) echo 2;; 2) echo 2,3;; 4) echo 2,3,4,5;; esac; }

ramp_for() { # $1 mode, $2 cores
  case "$1:$2" in
    grpc-unary:1)  echo "5000 10000 15000 20000 25000";;
    grpc-unary:2)  echo "10000 20000 30000 40000 50000";;
    grpc-unary:4)  echo "20000 40000 60000 80000 100000";;
    grpc-stream:1) echo "20000 40000 60000 80000 100000";;
    grpc-stream:2) echo "40000 80000 120000 160000 200000";;
    grpc-stream:4) echo "80000 160000 240000 320000 400000";;
  esac
}

run_one() { # $1 executor, $2 cores, $3 shape
  local exec="$1" cores="$2" shape="$3" mode mcs label ramp
  # One driver worker for the single-connection shapes (each worker owns at
  # least one connection); four workers for the eight-connection shapes so
  # the driver is not the limit at 4 server cores. 40 streams / 4 workers /
  # MCS 5 = 8 connections; 8 connections / 4 workers with MCS 1 = 8.
  local conc
  case "$shape" in
    stream-1conn)  mode=grpc-stream; mcs=512; conc=1;;
    stream-8conn)  mode=grpc-stream; mcs=5;   conc=4;;
    unary-default) mode=grpc-unary;  mcs=512; conc=1;;
    unary-8conn)   mode=grpc-unary;  mcs=1;   conc=4;;
  esac
  label="${exec}-${cores}c-${shape}"
  ramp="$(ramp_for "$mode" "$cores")"
  [ -s "$RES/$label.log" ] && { log "skip $label (done)"; return; }
  docker rm -f srv >/dev/null 2>&1 || true
  docker run -d --name srv --network host --cpuset-cpus "$(cpuset_for "$cores")" --cpus "$cores" --memory 1g \
    -e PORT=8080 -e EXECUTOR="$exec" -e JAVA_TOOL_OPTIONS=-Dclojure.compiler.direct-linking=true \
    -v "$W/app.jar:/app.jar:ro" "$BASE" -Xmx512m -Dio.netty.leakDetection.level=disabled -jar /app.jar >/dev/null
  for i in $(seq 1 60); do curl -sf --max-time 2 localhost:9090/metrics >/dev/null && break; sleep 1; done
  log "start $label ramp='$ramp' cpuset=$(cpuset_for "$cores")"
  echo "#NH-RUN $label start=$(date +%s)" >> "$RES/steps.log"
  docker run --rm --network host --cpuset-cpus "$DRIVER_CPUS" --cpus 4 --tmpfs /tmp --entrypoint /bin/sh \
    -v "$W/bodies:/bodies:ro" -v "$W/nh:/nh:ro" \
    -e TARGET=127.0.0.1 -e MODE="$mode" -e TIER=realistic -e RAMP="$ramp" -e STEP_SECONDS=60 \
    -e WARMUP_RPS=2000 -e WARMUP_SECONDS=90 -e CONCURRENCY="$conc" -e IDLE_STRATEGY=spin -e BODY_VIA_FILE=yes \
    -e HTTP1_CONNECTIONS=256 -e HTTP2_CONNECTIONS=8 -e MAX_ACTIVE_REQUESTS=4096 -e MAX_CONCURRENT_STREAMS="$mcs" \
    -e STREAMS=40 -e INFLIGHT=256 -e STREAM_DRAIN=0.5s \
    "$NH" /nh/run.sh > "$RES/$label.log.tmp" 2> "$RES/$label.err" || log "driver exited $? on $label"
  mv "$RES/$label.log.tmp" "$RES/$label.log"
  echo "#NH-RUN $label end=$(date +%s)" >> "$RES/steps.log"
  restarts=$(docker inspect -f '{{.RestartCount}}' srv 2>/dev/null || echo '?')
  docker rm -f srv >/dev/null 2>&1 || true
  {
    echo "### local:${exec}:${cores}c:${shape}:${mode}:realistic:${ramp} (mcs=${mcs}; restarts=${restarts}; x86 i9-7900X, cpuset $(cpuset_for "$cores"), driver ${DRIVER_CPUS} concurrency ${conc})"
    "$REPO/soak/collect.sh" "$mode" < "$RES/$label.log"
    echo
  } >> "$RES/tables.md"
  log "done $label"
}

mkdir -p "$RES"
for cores in 1 2 4; do
  for shape in stream-1conn unary-default stream-8conn unary-8conn; do
    for exec in direct virtual; do run_one "$exec" "$cores" "$shape"; done
  done
done
log "matrix complete"
