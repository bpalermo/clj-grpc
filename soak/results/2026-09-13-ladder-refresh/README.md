# The ladder refreshed on chart 0.2.27 — 2026-09-13

Every figure in the ladder table predated chart 0.2.23's defaults
(`-Xmn256m`, `:inbound-credits 8`) and the typed-slot read path (0.2.27).
Same ramps as the 2026-09-11 medians, extended one step; `:direct`,
realistic tier, 1-CPU pods; node CPU sampled on worker-03/04 (never above
3.5 of 4); zero restarts in ten runs; run in a window agreed with the
aether session (only its permanent 100m prober co-resident, as in every
earlier number). `PREDICTION.md` was written first.

| rung | replicates | median | spread | before (0.2.21/22) | Δ |
|---|---|---|---|---|---|
| REST HTTP/1.1 | 819 / 854 | **836** | 4.2% | 795 | +5% |
| REST h2c | 857 / 843 (knee; collapses past it as before) | **850** | 1.6% | 800 | +6% |
| gRPC unary | 8,203 / 8,417 / 8,374 at 0.117–0.120 ms | **8,374** | 2.6% | 6,540 | **+28%** |
| gRPC stream | 17,102 / 17,012 / 16,604 at 0.050–0.052 ms | **17,012** | 3.0% | 14,000 | **+21.5%** |

Ratios over REST h1: unary **10.0×**, stream **20.3×** (were 8.2× / 17.6×).
L1 (unary ~8,300) and L3 (REST ~835/840) held; L2 (stream 16,000–16,500)
was exceeded — the typed read path's per-message saving shows on the
cluster's one-connection loop as it did on x86; L4 held.
