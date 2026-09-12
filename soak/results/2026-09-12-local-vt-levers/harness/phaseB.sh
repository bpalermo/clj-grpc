#!/usr/bin/env bash
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"; RES="$W/results3"
until grep -q 'vt levers complete' "$RES/runner.log" 2>/dev/null; do sleep 30; done
cd /home/palermo/code/bpalermo/clj-grpc && git checkout -q exp/request-batch && \
  bazel build //bench:coldstart_server_deploy.jar > "$W/bazel-exp.log" 2>&1 && \
  cp bazel-bin/bench/coldstart_server_deploy.jar "$W/exp.jar" && chmod a+r "$W/exp.jar"; rc=$?
git checkout -q main
[ $rc -eq 0 ] || { echo "EXP BUILD FAILED" >> "$RES/runner.log"; exit 1; }
echo "exp.jar staged" >> "$RES/runner.log"
# reuse the lever runner's run_one
sed -n '1,/^for shape in/p' "$W/vt-levers.sh" | sed '$d' > "$W/vt-levers-lib.sh"
# shellcheck disable=SC1090
source "$W/vt-levers-lib.sh"
run_one percall-1conn  "$J21" "$W/exp.jar" "-e CALL_EXECUTOR=percall" "" 512 1
run_one percall-8conn  "$J21" "$W/exp.jar" "-e CALL_EXECUTOR=percall" "" 5 4
run_one batch8-1conn   "$J21" "$W/exp.jar" "-e REQUEST_BATCH=8" "" 512 1
run_one batch128-1conn "$J21" "$W/exp.jar" "-e REQUEST_BATCH=128" "" 512 1
log "phase B complete"
