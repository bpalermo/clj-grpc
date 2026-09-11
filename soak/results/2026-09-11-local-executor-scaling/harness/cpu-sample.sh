#!/usr/bin/env bash
# Per-CPU busy fraction every 10 s from /proc/stat: <epoch> cpuN busy%
out="$1"; prev=""
while :; do
  now=$(date +%s)
  cur=$(grep -E '^cpu[0-9]+ ' /proc/stat)
  if [ -n "$prev" ]; then
    paste <(echo "$prev") <(echo "$cur") | awk -v t="$now" '{
      pt=$2+$3+$4+$5+$6+$7+$8; pi=$5+$6; ct=$13+$14+$15+$16+$17+$18+$19; ci=$16+$17;
      d=ct-pt; if (d>0) printf "%s %s %.1f\n", t, $1, 100*(1-(ci-pi)/d) }' >> "$out"
  fi
  prev="$cur"; sleep 10
done
