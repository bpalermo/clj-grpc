#!/usr/bin/env bash
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"; RES="$W/results4"
source "$W/vt-levers-lib.sh"; RES="$W/results4"
J="$W/opt2.jar"; C="-e INBOUND_CREDITS=8"; Y="-Xmn256m"
run_one base-1conn   "$J21" "$J" "$C" "$Y" 512 1
run_one base-8conn   "$J21" "$J" "$C" "$Y" 5 4
run_one loops2-8conn "$J21" "$J" "$C -e WORKER_THREADS=2" "$Y" 5 4
run_one loops1-8conn "$J21" "$J" "$C -e WORKER_THREADS=1" "$Y" 5 4
run_one loops4-8conn "$J21" "$J" "$C -e WORKER_THREADS=4" "$Y" 5 4
run_one win4m-1conn  "$J21" "$J" "$C -e FLOW_WINDOW=4194304" "$Y" 512 1
run_one win16m-1conn "$J21" "$J" "$C -e FLOW_WINDOW=16777216" "$Y" 512 1
run_one win16m-loops2-8conn "$J21" "$J" "$C -e FLOW_WINDOW=16777216 -e WORKER_THREADS=2" "$Y" 5 4
run_one par3-1conn   "$J21" "$J" "$C" "$Y -Djdk.virtualThreadScheduler.parallelism=3" 512 1
run_one par2-loops2-8conn "$J21" "$J" "$C -e WORKER_THREADS=2" "$Y -Djdk.virtualThreadScheduler.parallelism=2" 5 4
log "plan123 complete"
