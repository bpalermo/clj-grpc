#!/usr/bin/env bash
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"
until grep -q 'items 2 and 3 complete' "$W/results6/runner.log" 2>/dev/null; do sleep 30; done
source "$W/vt-levers-lib.sh"; RES="$W/results6"
C="-e INBOUND_CREDITS=8"; Y="-Xmn256m"
# same fixture, same defaults, before (#102 branch pre-rebase = no #103) vs after #103 (reply.jar, REPLY=full)
(setsid nohup "$W/thread-at-top6.sh" "pre103-1conn" >/dev/null 2>&1 &)
run_one pre103-1conn "$J21" "$W/slot.jar" "$C" "$Y" 512 1
(setsid nohup "$W/thread-at-top6.sh" "post103-1conn" >/dev/null 2>&1 &)
run_one post103-1conn "$J21" "$W/reply.jar" "$C -e REPLY=full" "$Y" 512 1
log "pre/post 103 complete"
