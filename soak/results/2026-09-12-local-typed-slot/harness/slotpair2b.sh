#!/usr/bin/env bash
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"
until grep -q 'slot pair complete' "$W/results5/runner.log" 2>/dev/null; do sleep 30; done
quiet() { ! top -b -c -n 2 -d 2 -w 300 | awk 'p && NR>7 && /java|bazel|native-image|criterium|clojure/ && !/app.jar/ && $9+0>5 {f=1} /^top -/{n++; if(n==2)p=1} END{exit !f}'; }
n=0; until [ $n -ge 3 ]; do if quiet; then n=$((n+1)); else n=0; echo "$(date +%H:%M:%S) host busy, waiting" >&2; fi; sleep 5; done
source "$W/vt-levers-lib.sh"; RES="$W/results5"
C="-e INBOUND_CREDITS=8"; Y="-Xmn256m"
for shape in "vt-1conn virtual 512 1 ''" "vt-8conn virtual 5 4 '-e WORKER_THREADS=2'" "direct-1conn direct 512 1 ''" "direct-8conn direct 5 4 ''"; do
  eval "set -- $shape"; sh=$1; ex=$2; mcs=$3; conc=$4; extra=$5
  for jar in main slot; do
    run_one "${jar}2-$sh" "$J21" "$W/$jar.jar" "$C -e EXECUTOR=$ex $extra" "$Y" $mcs $conc
  done
done
log "slot pair 2 complete"
