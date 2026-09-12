# Chart 0.2.23 on the cluster — both defaults shipped — 2026-09-12

`-Xmn256m` on every JVM arm and `:inbound-credits 8` on the gRPC arms, both
verified on the pod. 1-CPU pods, realistic tier, `PREDICTION.md` first, node
CPU on worker-03 never above 3.9 of 4, zero restarts.

| run | executor | arm | knee / plateau | CPU per msg | vs before |
|---|---|---|---|---|---|
| `direct-unary` | `:direct` | compiled | ~8,300 rps at 0.119 ms | | +3% vs `-Xmn` alone (8,050); +27% vs the ladder median 6,540 — credits touch streaming only (K1 held) |
| `direct-stream` | `:direct` | compiled | ~16,000 msg/s at 0.054 | | = `-Xmn` alone; the credits are nil under `:direct` — the one-per-message request already ran on the loop (K2: capacity held, no CPU drop) |
| `vt-compiled` | virtual threads | compiled | knee ~13,400, plateau ~13,900 at 0.069–0.071 | | **+76%** vs the last VT baseline (~7,900, chart 0.2.8); 0.87× `:direct` (K3 held) |
| `vt-interop` | virtual threads | interop (plugin 0.6.0) | plateau ~16,000 at 0.059–0.060 | | −4 to −7% CPU/msg below the knee, **−14–15% at saturation**, +15% plateau vs `vt-compiled`; p50 lower at every step (K4: held at saturation, marginal below) |

Reading: on one CPU the shipped defaults bring virtual-thread streaming to
87% of `:direct`, and the typed interop arm on virtual threads reaches
`:direct`'s compiled plateau (~16,000). The ladder table in the doc predates
both defaults and says so.
