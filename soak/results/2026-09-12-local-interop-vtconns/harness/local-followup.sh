#!/usr/bin/env bash
set -euo pipefail
W="$(cd "$(dirname "$0")" && pwd)"; REPO=/home/palermo/code/bpalermo/clj-grpc; RES="$W/results2"
NH=ghcr.io/bpalermo/nighthawk:bc9de452fa39efd6d4ddd00c0b545cde275eddae
BASE=gcr.io/distroless/java21-debian12@sha256:f34fd3e4e2d7a246d764d0614f5e6ffb3a735930723fac4cfc25a72798950262
log() { echo "$(date +%H:%M:%S) $*" >&2; }
cpuset_for() { case "$1" in 1) echo 2;; 2) echo 2,3;; 4) echo 2,3,4,5;; esac; }
# run_one LABEL JAR EXEC CORES MODE MCS CONC RAMP [DRIVER_CPUS DRIVER_QUOTA]
run_one() {
  local label="$1" jar="$2" exec="$3" cores="$4" mode="$5" mcs="$6" conc="$7" ramp="$8" dcpus="${9:-6,7,8,9}" dquota="${10:-4}"
  [ -s "$RES/$label.log" ] && { log "skip $label"; return; }
  docker rm -f srv >/dev/null 2>&1 || true
  docker run -d --name srv --network host --cpuset-cpus "$(cpuset_for "$cores")" --cpus "$cores" --memory 1g \
    -e PORT=8080 -e EXECUTOR="$exec" -e JAVA_TOOL_OPTIONS=-Dclojure.compiler.direct-linking=true \
    -v "$jar:/app.jar:ro" "$BASE" -Xmx512m -Dio.netty.leakDetection.level=disabled -jar /app.jar >/dev/null
  for i in $(seq 1 60); do curl -sf --max-time 2 localhost:9090/metrics >/dev/null && break; sleep 1; done
  log "start $label ramp='$ramp'"; echo "#NH-RUN $label start=$(date +%s)" >> "$RES/steps.log"
  docker run --rm --network host --cpuset-cpus "$dcpus" --cpus "$dquota" --tmpfs /tmp --entrypoint /bin/sh \
    -v "$W/bodies:/bodies:ro" -v "$W/nh:/nh:ro" \
    -e TARGET=127.0.0.1 -e MODE="$mode" -e TIER=realistic -e RAMP="$ramp" -e STEP_SECONDS=60 \
    -e WARMUP_RPS=2000 -e WARMUP_SECONDS=90 -e CONCURRENCY="$conc" -e IDLE_STRATEGY=spin -e BODY_VIA_FILE=yes \
    -e HTTP1_CONNECTIONS=256 -e HTTP2_CONNECTIONS=8 -e MAX_ACTIVE_REQUESTS=4096 -e MAX_CONCURRENT_STREAMS="$mcs" \
    -e STREAMS=40 -e INFLIGHT=256 -e STREAM_DRAIN=0.5s "$NH" /nh/run.sh > "$RES/$label.log.tmp" 2> "$RES/$label.err" || log "driver exited $? on $label"
  mv "$RES/$label.log.tmp" "$RES/$label.log"; echo "#NH-RUN $label end=$(date +%s)" >> "$RES/steps.log"
  docker rm -f srv >/dev/null 2>&1 || true
  { echo "### local:${label} (jar=$(basename "$jar"); exec=${exec}; cores=${cores}; mode=${mode}; mcs=${mcs}; workers=${conc}; ramp=${ramp}; x86 i9-7900X, cpuset $(cpuset_for "$cores"), driver ${dcpus})"
    "$REPO/soak/collect.sh" "$mode" < "$RES/$label.log"; echo; } >> "$RES/tables.md"
  log "done $label"
}
until [ -s "$W/interop.jar" ]; do sleep 20; done
C="$W/app.jar"; I="$W/interop.jar"; D8=6,7,8,9,16,17,18,19
# Track 2: interop vs compiled, :direct
run_one compiled-1c-stream-8conn "$C" direct 1 grpc-stream 5 4 "60000 90000 120000 150000 180000"
run_one interop-1c-stream-8conn  "$I" direct 1 grpc-stream 5 4 "60000 90000 120000 150000 180000"
run_one compiled-4c-stream-8conn "$C" direct 4 grpc-stream 5 4 "160000 240000 320000 400000"
run_one interop-4c-stream-8conn  "$I" direct 4 grpc-stream 5 4 "160000 240000 320000 400000"
run_one compiled-4c-unary-8conn  "$C" direct 4 grpc-unary 4 8 "80000 120000 160000 200000 240000" $D8 8
run_one interop-4c-unary-8conn   "$I" direct 4 grpc-unary 4 8 "80000 120000 160000 200000 240000" $D8 8
log "track 2 complete"
# Track 3: VT with 2 and 4 connections at 4 cores
run_one virtual-4c-stream-2conn "$C" virtual 4 grpc-stream 20 1 "160000 240000 320000 400000"
run_one virtual-4c-stream-4conn "$C" virtual 4 grpc-stream 10 1 "160000 240000 320000 400000"
log "track 3 complete"
