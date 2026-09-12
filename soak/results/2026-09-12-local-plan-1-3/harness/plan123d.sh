#!/usr/bin/env bash
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"
# quiet = no non-container java above 5% INSTANTANEOUS CPU (top's second sample; ps pcpu is a lifetime average)
quiet() { ! top -b -n 2 -d 2 | awk 'p && /java/ && !/app.jar/ && $9+0>5 {f=1} /^top -/{n++; if(n==2)p=1} END{exit !f}'; }
n=0; until [ $n -ge 3 ]; do if quiet; then n=$((n+1)); else n=0; echo "$(date +%H:%M:%S) host busy (peer JVM), waiting" >&2; fi; sleep 5; done
source "$W/vt-levers-lib.sh"; RES="$W/results4"
J="$W/opt2.jar"; C="-e INBOUND_CREDITS=8"; Y="-Xmn256m"
run_one win4m2-1conn  "$J21" "$J" "$C -e FLOW_WINDOW=4194304" "$Y" 512 1
run_one win16m2-1conn "$J21" "$J" "$C -e FLOW_WINDOW=16777216" "$Y" 512 1
run_one win16m2-loops2-8conn "$J21" "$J" "$C -e FLOW_WINDOW=16777216 -e WORKER_THREADS=2" "$Y" 5 4
log "window re-runs complete"
