#!/usr/bin/env bash
set -euo pipefail
W="$(cd "$(dirname "$0")" && pwd)"; REPO=/home/palermo/code/bpalermo/clj-grpc; RES="$W/results3"
NH=ghcr.io/bpalermo/nighthawk:bc9de452fa39efd6d4ddd00c0b545cde275eddae
J21=gcr.io/distroless/java21-debian12@sha256:f34fd3e4e2d7a246d764d0614f5e6ffb3a735930723fac4cfc25a72798950262
J25=eclipse-temurin:25-jre
log() { echo "$(date +%H:%M:%S) $*" >&2; }
# run_one LABEL IMAGE JAR "EXTRA_ENV" "EXTRA_JVM" MCS CONC
run_one() {
  local label="$1" image="$2" jar="$3" eenv="$4" ejvm="$5" mcs="$6" conc="$7" ramp="160000 240000 320000 400000"
  [ -s "$RES/$label.log" ] && { log "skip $label"; return; }
  docker rm -f srv >/dev/null 2>&1 || true
  local entry=(); [ "$image" = "$J25" ] && entry=(java)
  # shellcheck disable=SC2086
  docker run -d --name srv --network host --cpuset-cpus 2,3,4,5 --cpus 4 --memory 1g \
    -e PORT=8080 -e EXECUTOR=virtual -e JAVA_TOOL_OPTIONS=-Dclojure.compiler.direct-linking=true $eenv \
    -v "$jar:/app.jar:ro" "$image" "${entry[@]}" -Xmx512m -Dio.netty.leakDetection.level=disabled $ejvm -jar /app.jar >/dev/null
  for i in $(seq 1 60); do curl -sf --max-time 2 localhost:9090/metrics >/dev/null && break; sleep 1; done
  log "start $label"; echo "#NH-RUN $label start=$(date +%s)" >> "$RES/steps.log"
  docker run --rm --network host --cpuset-cpus 6,7,8,9 --cpus 4 --tmpfs /tmp --entrypoint /bin/sh \
    -v "$W/bodies:/bodies:ro" -v "$W/nh:/nh:ro" \
    -e TARGET=127.0.0.1 -e MODE=grpc-stream -e TIER=realistic -e RAMP="$ramp" -e STEP_SECONDS=60 \
    -e WARMUP_RPS=2000 -e WARMUP_SECONDS=90 -e CONCURRENCY="$conc" -e IDLE_STRATEGY=spin -e BODY_VIA_FILE=yes \
    -e HTTP1_CONNECTIONS=256 -e HTTP2_CONNECTIONS=8 -e MAX_ACTIVE_REQUESTS=4096 -e MAX_CONCURRENT_STREAMS="$mcs" \
    -e STREAMS=40 -e INFLIGHT=256 -e STREAM_DRAIN=0.5s "$NH" /nh/run.sh > "$RES/$label.log.tmp" 2> "$RES/$label.err" || log "driver exited $? on $label"
  mv "$RES/$label.log.tmp" "$RES/$label.log"; echo "#NH-RUN $label end=$(date +%s)" >> "$RES/steps.log"
  docker logs srv 2>&1 | grep -i -E 'picked up|error|exception' | head -3 > "$RES/$label.srv" || true
  docker rm -f srv >/dev/null 2>&1 || true
  { echo "### local:${label} (image=${image##*/}; jar=$(basename "$jar"); env='${eenv}'; jvm='${ejvm}'; mcs=${mcs}; workers=${conc}; VT, 4 cores)"
    "$REPO/soak/collect.sh" grpc-stream < "$RES/$label.log"; echo; } >> "$RES/tables.md"
  log "done $label"
}
for shape in "1conn 512 1" "8conn 5 4"; do
  set -- $shape; sh=$1; mcs=$2; conc=$3
  run_one base-$sh    "$J21" "$W/app.jar"   "" "" $mcs $conc
  run_one jdk25-$sh   "$J25" "$W/app.jar"   "" "" $mcs $conc
  run_one loops2-$sh  "$J21" "$W/app.jar"   "" "-Dio.netty.eventLoopThreads=2" $mcs $conc
  run_one pargc-$sh   "$J21" "$W/app.jar"   "" "-XX:+UseParallelGC" $mcs $conc
  run_one batch32-$sh "$J21" "$W/batch.jar" "-e REQUEST_BATCH=32" "" $mcs $conc
done
log "vt levers complete"
