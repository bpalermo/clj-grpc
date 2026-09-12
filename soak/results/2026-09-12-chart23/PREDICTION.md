# Pre-registered — chart 0.2.23 on the cluster (both defaults shipped), 2026-09-12

Chart 0.2.23 = `-Xmn256m` on every JVM arm + `:inbound-credits 8` on the
gRPC arms. 1-CPU pods, realistic tier, node CPU sampled. Baselines: the
ladder medians (6,540 unary / 14,000 stream, `:direct`) and today's
`-Xmn`-only runs (8,050 / 16,000).
- K1 `:direct` unary: within ±4% of 8,050 (credits touch streaming only).
- K2 `:direct` stream: ≥ 16,000 and CPU/msg ≤ 0.054 − 5%; the one-connection
  loop cap (~0.88 core) bounds capacity, so read CPU/msg first.
- K3 VT stream, compiled arm: the x86 stacked levers put VT at 85–90% of
  `:direct`; predicted ≥ 13,000 (≥ 0.8× K2), from ~7,900 on chart 0.2.8.
- K4 VT stream, interop arm, paired on worker-03: −10–20% CPU/msg vs K3
  (the whole typed path; plugin 0.6.0), nil below 4%.
