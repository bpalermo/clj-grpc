#!/usr/bin/env bash
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"
source "$W/vt-levers-lib.sh"; RES="$W/results8"
J="$W/marsh.jar"; C="-e INBOUND_CREDITS=8"; Y="-Xmn256m"
for shape in "vt-1conn virtual 512 1 ''" "vt-8conn virtual 5 4 '-e WORKER_THREADS=2'" "direct-1conn direct 512 1 ''"; do
  eval "set -- $shape"; sh=$1; ex=$2; mcs=$3; conc=$4; extra=$5
  run_one "off-$sh" "$J21" "$J" "$C -e EXECUTOR=$ex $extra" "$Y" $mcs $conc
  run_one "on-$sh"  "$J21" "$J" "$C -e EXECUTOR=$ex $extra" "$Y -Dclj-grpc.marshaller=direct" $mcs $conc
done
log "marshaller pair complete"
