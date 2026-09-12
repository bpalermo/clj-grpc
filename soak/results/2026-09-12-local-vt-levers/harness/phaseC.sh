#!/usr/bin/env bash
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"; RES="$W/results3"
until grep -q 'phase B complete\|EXP BUILD FAILED' "$RES/runner.log" 2>/dev/null; do sleep 30; done
source "$W/vt-levers-lib.sh"
run_one serialyoung-1conn "$J21" "$W/app.jar" "" "-Xmn256m" 512 1
run_one g1-1conn          "$J21" "$W/app.jar" "" "-XX:+UseG1GC" 512 1
run_one zgc-1conn         "$J21" "$W/app.jar" "" "-XX:+UseZGC -XX:+ZGenerational" 512 1
run_one pargcyoung-1conn  "$J21" "$W/app.jar" "" "-XX:+UseParallelGC -Xmn256m" 512 1
run_one direct-pargc-8conn "$J21" "$W/app.jar" "-e EXECUTOR=direct" "-XX:+UseParallelGC" 5 4
log "phase C complete"
