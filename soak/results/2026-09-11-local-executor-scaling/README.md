# Executor scaling with cores — local x86, 2026-09-11

Does the virtual-thread executor scale with cores where `:direct` cannot, and
what does each cost per request as cores grow? The cluster cannot host this (a
2-core pod is already at the node's ceiling), so it ran on an i9-7900X (10
physical cores, HT siblings left idle): server pinned to physical cores 2 /
2–3 / 2–5 with a matching CPU quota, driver on 6–9 (unary top-ups: 6–9 plus
siblings, eight workers), both on host networking over loopback. Same deploy
jar on the same distroless base digest as the cluster image, same bodies,
same Nighthawk fork image, the chart's `run.sh` and `soak/collect.sh`
unchanged. `PREDICTION.md` was written before the first run; `harness/` is
the runner. Conclusions in `../../../docs/soak-results.md`, "The executor,
and cores".

Shapes: `stream-1conn` (40 streams, one connection), `stream-8conn` (40
streams, `--max-concurrent-streams 5` → 8 connections), `unary-default`
(connections as the client pool needs them, one driver worker), `unary-8conn`
(8 connections; matrix rows MCS 1 with four workers, `-top` rows MCS 4 with
eight workers). Warmup 2,000 rps × 90 s, five 60 s steps; matrix ramps then
`-top` re-runs on higher ramps where the first ramp stopped at or before the
knee. Rows where the driver was at its limit: `unary-default` at 2 and 4
cores (one worker, 80–97% of its core), and the 1-core `direct
unary-default-top` top step (server 0.93 cores, driver 97–100%).

`tables.md` is every run; `*.log.gz` the Job-format logs; `cpu-samples.tsv.gz`
per-CPU busy % every 10 s for the whole session; `steps.log` run windows;
`verify-threads.txt` per-thread CPU of the VT server at 4 cores, one
connection, 240k msg/s; the `-inflight1024` rows are the client in-flight
control for the 4-core eight-connection plateau.

## Summary — knee (msg/s or rps) and CPU per message at it

| shape | cores | `:direct` | virtual threads | note |
|---|---|---|---|---|
| stream, 1 conn | 1 | ~123,000 at 0.008 ms | ~66,000 at 0.015 | direct from `-top` |
| stream, 1 conn | 2 | ~92,000 at 0.011 (1.0 core) | ~121,000 at 0.015 (1.8 cores) | |
| stream, 1 conn | 4 | ~94,000 at 0.011 (1.0 core) | ~237,000 at 0.013 (3.1 cores) | VT loop thread 82%, carriers 52% |
| stream, 8 conn | 1 | ~113,000 at 0.009 | ~55,000 at 0.018 | direct from `-top` |
| stream, 8 conn | 2 | ~179,000 at 0.010 (1.8 cores) | ~105,000 at 0.018 (1.9 cores) | |
| stream, 8 conn | 4 | ~268,000 at 0.010 (2.7 cores) | ~163,000 at 0.020 (3.3 cores) | in-flight ×4: 281,000 / 179,000 |
| unary, 1 conn | 1 | ~58,000 at 0.016 (0.93 core) | ~39,000 at 0.026 | driver 97–100% on direct's top step |
| unary, 1 conn | 2 | 50,000 at 0.021 (1.05 cores) | 50,000 at 0.033 (1.65 cores) | matched rate; driver-limited beyond |
| unary, 1 conn | 4 | ~59,000 (2.2 cores) | ~58,000 (2.4 cores) | **driver ceiling**, not a knee |
| unary, 8 conn | 1 | ~51,500 at 0.019 (0.98 core) | ~40,600 at 0.025 | `-top` rows, 8 workers |
| unary, 8 conn | 2 | ~94,000 at 0.020 (1.9 cores) | ~61,000 at 0.031 (1.9 cores) | `-top` rows |
| unary, 8 conn | 4 | ~136,000 at 0.024 (3.3 cores) | ~92,500 at 0.038 (3.5 cores) | direct: driver 6.2/8 — a floor |

Predictions: P1 held on streaming (13–33%) and failed high on unary (36–67%);
P2 held (direct ±20% of one core at every count; VT 2.5× at 4 cores); P3 held
on throughput scaling and on direct staying cheaper, and its "within 20%"
clause failed the other way — direct is 1.5–1.7× VT with eight connections;
P4 held on streaming (flat) and failed on the line for unary (1.52×).
