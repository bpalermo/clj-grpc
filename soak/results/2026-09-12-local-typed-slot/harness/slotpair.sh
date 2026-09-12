#!/usr/bin/env bash
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"
source "$W/vt-levers-lib.sh"; RES="$W/results5"
C="-e INBOUND_CREDITS=8"; Y="-Xmn256m"
for shape in "vt-1conn virtual 512 1 ''" "vt-8conn virtual 5 4 '-e WORKER_THREADS=2'" "direct-1conn direct 512 1 ''" "direct-8conn direct 5 4 ''"; do
  eval "set -- $shape"; sh=$1; ex=$2; mcs=$3; conc=$4; extra=$5
  for jar in main slot; do
    run_one "$jar-$sh" "$J21" "$W/$jar.jar" "$C -e EXECUTOR=$ex $extra" "$Y" $mcs $conc
  done
done
log "slot pair complete"
