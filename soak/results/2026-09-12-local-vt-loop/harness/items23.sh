#!/usr/bin/env bash
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"
until [ -s "$W/reply.jar" ]; do sleep 10; done
source "$W/vt-levers-lib.sh"; RES="$W/results6"
J="$W/reply.jar"; C="-e INBOUND_CREDITS=8"; Y="-Xmn256m"
# item 2: the write side's share, one connection
for m in full tiny none; do
  (setsid nohup "$W/thread-at-top6.sh" "reply-$m-1conn" >/dev/null 2>&1 &)
  run_one "reply-$m-1conn" "$J21" "$J" "$C -e REPLY=$m" "$Y" 512 1
done
# item 3: one vs two connections, defaults + two loops (MCS 40 -> 1 connection, 20 -> 2)
run_one conn1-loops2 "$J21" "$J" "$C -e WORKER_THREADS=2" "$Y" 40 1
run_one conn2-loops2 "$J21" "$J" "$C -e WORKER_THREADS=2" "$Y" 20 1
log "items 2 and 3 complete"
