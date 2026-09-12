#!/usr/bin/env bash
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"; RES="$W/results3"
source "$W/vt-levers-lib.sh"
run_one opt-1conn "$J21" "$W/opt.jar" "-e INBOUND_CREDITS=8" "-Xmn256m" 512 1
run_one opt-loops2-8conn "$J21" "$W/opt.jar" "-e INBOUND_CREDITS=8" "-Xmn256m -Dio.netty.eventLoopThreads=2" 5 4
run_one opt-direct-8conn "$J21" "$W/opt.jar" "-e EXECUTOR=direct -e INBOUND_CREDITS=8" "-Xmn256m" 5 4
log "phase opt complete"
