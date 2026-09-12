#!/usr/bin/env bash
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"; RES="$W/results3"
until grep -q 'phase C complete' "$RES/runner.log" 2>/dev/null; do sleep 30; done
source "$W/vt-levers-lib.sh"
J="$W/exp.jar"; [ -s "$J" ] || J="$W/batch.jar"
run_one combo-1conn        "$J21" "$J" "-e REQUEST_BATCH=32" "-XX:+UseParallelGC" 512 1
run_one combo-8conn        "$J21" "$J" "-e REQUEST_BATCH=32" "-XX:+UseParallelGC" 5 4
run_one combo-loops2-8conn "$J21" "$J" "-e REQUEST_BATCH=32" "-XX:+UseParallelGC -Dio.netty.eventLoopThreads=2" 5 4
run_one combo-percall-1conn "$J21" "$W/exp.jar" "-e REQUEST_BATCH=32 -e CALL_EXECUTOR=percall" "-XX:+UseParallelGC" 512 1
log "phase D complete"
