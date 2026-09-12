# Pre-registered — plan items 1–3 on the shipped defaults, local x86, 2026-09-12

Same pinned harness; VT, 4 server cores; every run carries the chart's
defaults (`-Xmn256m`, `INBOUND_CREDITS=8`) via `opt2.jar` (feat/worker-
threads-flow-window). Same-session baselines: `base-1conn`, `base-8conn`
(previous stacked numbers ~445k / ~398k with two loops).

- Item 1, `:worker-threads` on eight connections: loops 1, 2, 4 vs default
  8. Prediction: 2 ≥ +15% over 8; 1 within ±5% of 2; 4 between. One
  connection: nil (not run again — measured nil twice).
- Item 2, `:initial-flow-control-window` 4 MiB and 16 MiB vs default 1 MiB:
  one connection (the loop-bound shape) and eight (with 2 loops).
  Prediction: nil on throughput (BDP already grows the window); the readout
  that matters is the loop thread's share at a held rate, taken with the
  /proc per-thread sampler on the 16 MiB one-connection run.
- Item 3, `-Djdk.virtualThreadScheduler.parallelism`: 3 on one connection
  (cores − 1 loop), 2 on eight connections with 2 loops. Prediction: one
  connection +5–10%; eight nil.
Nil below +4%.

## Note (09:20, before any lever row was read): the first two baselines are perturbed
`bazel test //...` and a jar build ran on this host from 08:59 to ~09:06,
overlapping `base-1conn` entirely and the start of `base-8conn`. `base-1conn`
came in at ~344k against ~395k for the same configuration the night before.
Both baselines are re-run as `base2-*` after the lever rows, with the host
quiet; the lever rows are read against `base2-*`.

## Note (09:50): window rows perturbed by a peer benchmark on this host
The clj-protobuf session's bench (four unpinned bazel JVMs at 10–36% each)
ran from ~09:34, overlapping `win4m-1conn` and `win16m-1conn` (and possibly
`win16m-loops2-8conn`). All three are re-run as `*2` rows after the clean
baselines, gated on the host being quiet (no non-container JVM above 5% CPU
for 60 s). The peer was asked to hold heavy runs until the queue ends.
