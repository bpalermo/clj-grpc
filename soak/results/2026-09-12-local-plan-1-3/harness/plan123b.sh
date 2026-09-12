#!/usr/bin/env bash
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"
until grep -q 'plan123 complete' "$W/results4/runner.log" 2>/dev/null; do sleep 30; done
source "$W/vt-levers-lib.sh"; RES="$W/results4"
J="$W/opt2.jar"; C="-e INBOUND_CREDITS=8"; Y="-Xmn256m"
run_one base2-1conn "$J21" "$J" "$C" "$Y" 512 1
run_one base2-8conn "$J21" "$J" "$C" "$Y" 5 4
log "plan123b complete"
