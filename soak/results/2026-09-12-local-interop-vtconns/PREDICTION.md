# Pre-registered — interop at 4 cores, and VT connection count — local x86, 2026-09-12

Same pinned harness as 2026-09-11-local-executor-scaling (server cpus 2–5,
driver 6–9 (+ siblings for eight-worker unary), loopback, same bodies, same
Nighthawk fork, run.sh and collect.sh unchanged).

## Track 2 — typed interop vs compiled, `:direct`
Cluster pairs said interop is −3–4% CPU at 1 CPU and −9–12% at 2 cores, but
both 2-core pairs were host-limited. Here: 1 core and 4 cores, streaming on
eight connections (MCS 5, four workers) and unary on eight (MCS 4, eight
workers), interleaved compiled/interop. Jars: the same coldstart server built
against the interop=true fixture (`//bench:coldstart_server_interop_deploy.jar`).
- P5 at matched rates below the knee interop is cheaper per message by
  8–20% at 4 cores and 3–8% at 1 core; nil if |Δ| < 4% (the replicate floor).
- P6 interop's plateau at 4 cores is 8–20% higher on streaming.

## Track 3 — virtual threads with 2 and 4 connections at 4 cores
VT's single-connection ceiling was the connection's loop at 82%; with eight
connections it was worse (163k at 0.020 vs 237k at 0.013). Streaming, 40
streams, MCS 20 → 2 connections and MCS 10 → 4, one worker.
- P7 two connections: plateau ≥ 1.2× the one-connection 237k (≥ 285k) using
  ≥ 3.6 cores.
- P8 four connections within ±15% of two; both cheaper per message than
  eight connections (0.020).
