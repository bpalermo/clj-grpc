# Typed interop at 4 cores, and virtual threads by connection count — local x86, 2026-09-12

Same pinned harness as `../2026-09-11-local-executor-scaling/` (server on
physical cores 2–5, driver on 6–9 plus siblings for eight-worker unary,
loopback, same bodies, same Nighthawk fork, `run.sh`/`collect.sh` unchanged);
`harness/local-followup.sh` is the runner, `PREDICTION.md` was written first.
The compiled `:direct` baselines reproduced the previous day's rows within
3–4% on every shape.

## Track 2 — typed interop (`interop=true` fixture) vs compiled, `:direct`

| shape | compiled | interop | reading |
|---|---|---|---|
| 1 core, stream, 8 conn | ~116,000 at 0.009 ms | ~142,000 at 0.007 | **+23% capacity, −22% CPU/msg** at the knee; equal below it |
| 4 cores, stream, 8 conn | ~278,000 at 0.010 | ~250,000 at 0.011 | −11% CPU at 160k; plateau not CPU-bound (68%/CPU), interop 10% lower there |
| 4 cores, unary, 8 conn | ~141,000 at 0.023 | ~142,000 at 0.022 | **nil** (0 to −4%, the replicate floor) |

P5 held at 1 core on streaming (beyond the predicted 3–8%) and failed at 4
cores on unary (nil); P6 failed (no plateau gain at 4 cores). With the
cluster's pairs (unary 1 CPU −3–4%; streaming 2 cores −9–12%) the variable
is the mode, not the core count: interop pays on streamed messages, where the
codec is a large share, and not on unary requests, where grpc-java's per-call
machinery is.

## Track 3 — virtual threads, 4 cores, streaming by connection count

| connections | plateau | CPU/msg | cores used |
|---|---|---|---|
| 1 (2026-09-11) | ~237,000 | 0.013 ms | 3.1 |
| 2 | ~203,000 | 0.015 | 3.0 |
| 4 | ~187,000 | 0.017 | ~3.2 |
| 8 (2026-09-11) | ~163,000 | 0.020 | 3.3 |

P7 and P8 failed in the same direction: for virtual threads every added
connection costs throughput and CPU per message, monotonically. `:direct` is
the mirror image (one connection ~92,000, eight ~268,000 on the same cores).
