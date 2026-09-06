#!/usr/bin/env bash
# Turn one load Job's log into the results tables.
#
# Input: the stdout of a Nighthawk Job (charts/clj-grpc-soak/files/nighthawk/run.sh):
# per step a `#NH-STEP {...}` header carrying the offered rate, the window,
# and the arm's /metrics before and after (base64); then Nighthawk's JSON;
# then `#NH-END`. Output: one Markdown table row per step, on stdout.
#
#   soak/collect.sh MODE < job.log
#   MODE is the Job's mode: http1 | http2 | grpc-unary | grpc-stream — it
#   decides which histogram is "the" latency and which counter is the knee.
#
# Every number here is derived from what the run actually recorded, never
# from what was offered: delivered/s is the success COUNT over the window,
# CPU per request is the arm's own cgroup counter delta over that count, and
# the knee signal is the counter Nighthawk increments when it could not send.
set -euo pipefail

mode="${1:?usage: collect.sh MODE < job.log}"

case "${mode}" in
  http1|http2)  latency_id="benchmark_http_client.latency_2xx";      ok_counter="benchmark.http_2xx";     knee_counter="benchmark.pool_overflow" ;;
  grpc-unary)   latency_id="benchmark_http_client.latency_grpc_ok";  ok_counter="benchmark.grpc_status.0"; knee_counter="benchmark.pool_overflow" ;;
  grpc-stream)  latency_id="benchmark_stream.message_latency";       ok_counter="";                        knee_counter="benchmark.stream_deferred" ;;
  *) echo "collect.sh: unknown mode '${mode}'" >&2; exit 2 ;;
esac

# The metrics snapshot is Prometheus text; pull one gauge/counter's value.
metric() { # $1 = base64 text, $2 = metric name
  printf '%s' "$1" | base64 -d 2>/dev/null | awk -v m="$2" '$1 == m { print $2; exit }'
}

echo "| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |"
echo "|---|---|---|---|---|---|---|---|---|---|"

header=""; body=""; in_step=0
while IFS= read -r line; do
  case "${line}" in
    "#NH-STEP "*) header="${line#\#NH-STEP }"; body=""; in_step=1 ;;
    "#NH-END")
      in_step=0
      rps=$(jq -r .rps <<<"${header}")
      warmup=$(jq -r .warmup <<<"${header}")
      before=$(jq -r .metrics_before <<<"${header}")
      after=$(jq -r .metrics_after <<<"${header}")
      # Nighthawk JSON: the aggregated result is the one named "global".
      # Durations are proto-JSON strings like "0.002345s"; percentiles are
      # HdrHistogram's fixed set, so pick the entry nearest each target.
      stats=$(jq -c --arg id "${latency_id}" --arg ok "${ok_counter}" --arg knee "${knee_counter}" '
        (.results[] | select(.name == "global")) as $g
        | ($g.execution_duration | sub("s$"; "") | tonumber) as $secs
        | ($g.counters | map({(.name): (.value | tonumber)}) | add // {}) as $c
        | ($g.statistics[] | select(.id == $id)) as $s
        | def pct(t): ($s.percentiles | min_by(((.percentile - t) | fabs)) | .duration | sub("s$"; "") | tonumber * 1000);
          { secs: $secs,
            ok: (if $ok == "" then $s.count else ($c[$ok] // 0) end),
            knee: ($c[$knee] // 0),
            p50: pct(0.5), p99: pct(0.99), p999: pct(0.999) }' <<<"${body}" || { echo "collect.sh: could not parse Nighthawk JSON for offered=${rps}" >&2; echo '{}'; })
      secs=$(jq -r '.secs // 0' <<<"${stats}"); ok=$(jq -r '.ok // 0' <<<"${stats}"); knee=$(jq -r '.knee // 0' <<<"${stats}")
      p50=$(jq -r '.p50 // "n/a"' <<<"${stats}"); p99=$(jq -r '.p99 // "n/a"' <<<"${stats}"); p999=$(jq -r '.p999 // "n/a"' <<<"${stats}")
      cpu_b=$(metric "${before}" cgroup_cpu_usage_seconds_total); cpu_a=$(metric "${after}" cgroup_cpu_usage_seconds_total)
      thr_b=$(metric "${before}" cgroup_cpu_throttled_seconds_total); thr_a=$(metric "${after}" cgroup_cpu_throttled_seconds_total)
      heap_b=$(metric "${before}" heap_used_bytes); heap_a=$(metric "${after}" heap_used_bytes)
      rss_a=$(metric "${after}" cgroup_memory_current_bytes)
      row=$(awk -v rps="${rps}" -v secs="${secs}" -v ok="${ok}" -v knee="${knee}" \
                -v p50="${p50}" -v p99="${p99}" -v p999="${p999}" \
                -v cb="${cpu_b:-}" -v ca="${cpu_a:-}" -v tb="${thr_b:-}" -v ta="${thr_a:-}" \
                -v hb="${heap_b:-}" -v ha="${heap_a:-}" -v rss="${rss_a:-}" -v warm="${warmup}" '
        function f(x, d) { return (x == "" || x == "n/a") ? "n/a" : sprintf("%." d "f", x) }
        BEGIN {
          delivered = (secs > 0) ? ok / secs : 0
          kneeps    = (secs > 0) ? knee / secs : 0
          cpu_ms    = (cb != "" && ca != "" && ok > 0) ? (ca - cb) * 1000 / ok : ""
          thr_s     = (tb != "" && ta != "") ? ta - tb : ""
          heap_mb   = (hb != "" || ha != "") ? ((hb > ha ? hb : ha) / 1048576) : ""
          rss_mb    = (rss != "") ? rss / 1048576 : ""
          tag = (warm == "true") ? " (warmup)" : ""
          printf "| %s%s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n",
            rps, tag, f(delivered,1), f(p50,2), f(p99,2), f(p999,2), f(kneeps,1), f(cpu_ms,3), f(thr_s,1), f(heap_mb,0), f(rss_mb,0)
        }')
      echo "${row}" ;;
    *) [ "${in_step}" = 1 ] && body="${body}${line}"$'\n' ;;
  esac
done
