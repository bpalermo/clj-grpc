#!/usr/bin/env bash
set -uo pipefail
W="$(cd "$(dirname "$0")" && pwd)"; R=/home/palermo/code/bpalermo/clj-grpc
until grep -q 'marshaller pair complete' "$W/results8/runner.log" 2>/dev/null; do sleep 30; done
# build the one-variable pair: 0.4.0 + old fixture (w0) vs 0.4.0 + branch fixture (w1)
for b in deps/clj-protobuf-0.4.0:w0 soak/typed-write-arm:w1; do
  br=${b%%:*}; jar=${b##*:}
  git -C $R checkout -q $br && (cd $R && bazel build //bench:coldstart_server_deploy.jar >/dev/null 2>&1) && rm -f "$W/$jar.jar" && cp $R/bazel-bin/bench/coldstart_server_deploy.jar "$W/$jar.jar" && chmod a+r "$W/$jar.jar" && echo "$(date +%H:%M:%S) built $jar from $br" >&2
done
git -C $R checkout -q main
until [ -f "$W/results9/GO" ]; do sleep 15; done
source "$W/vt-levers-lib.sh"; RES="$W/results9"
C="-e INBOUND_CREDITS=8"; Y="-Xmn256m"
for shape in "direct-1conn direct 512 1 ''" "vt-1conn virtual 512 1 ''" "vt-8conn virtual 5 4 '-e WORKER_THREADS=2'" "direct-8conn direct 5 4 ''"; do
  eval "set -- $shape"; sh=$1; ex=$2; mcs=$3; conc=$4; extra=$5
  for jar in w0 w1; do
    run_one "$jar-$sh" "$J21" "$W/$jar.jar" "$C -e EXECUTOR=$ex $extra" "$Y" $mcs $conc
  done
done
log "typed-write pair complete"
