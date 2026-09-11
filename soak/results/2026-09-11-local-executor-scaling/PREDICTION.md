# Pre-registered — executor scaling with cores, local x86, 2026-09-11

Question: does the virtual-thread executor scale with cores where `:direct`
cannot, and what does each cost per request as cores grow? The cluster cannot
host this (a 2-core pod is already at the node's ceiling), so it runs on a
quiet i9-7900X (10 physical cores, HT siblings left idle), server and driver
pinned to disjoint physical cores, same jar, same bodies, same Nighthawk fork,
same run.sh and collect.sh as the cluster.

Matrix: executor {direct, vt} × server cores {1, 2, 4} × shape
{stream-1conn (40 streams, MCS 512), stream-8conn (40 streams, MCS 5),
unary-default (MCS 512, connections as needed), unary-8conn (MCS 1)}.
Warmup 2,000 rps × 90 s, five 60 s steps. Interleaved direct/vt per config.
Driver: one worker for the single-connection shapes, four for the
eight-connection ones. Ramps were doubled after the first run (discarded,
kept under discarded/) delivered 50,000 msg/s on one core with no knee: the
x86 core is ~3× cheaper per message than the CM5, not ~2×.

Predictions, thresholds fixed before the first run:

- P1 At 1 core `:direct` is 15–27% cheaper per request than VT on every
  shape, as on the cluster. Fail if outside 5–40%.
- P2 Single-connection shapes: `:direct` does not scale — its 4-core plateau
  is within ±20% of its 1-core plateau. VT does: its 4-core stream-1conn
  plateau is ≥ 2× `:direct`'s at 4 cores.
- P3 Eight-connection shapes: both scale; `:direct` 4-core plateau ≥ 3× its
  1-core plateau; the two executors' 4-core plateaus are within 20% of each
  other; `:direct` stays 10–25% cheaper per request at every core count.
- P4 (the retraction test) VT's CPU per message at 4 cores is ≤ 1.3× its
  1-core value. The cluster's "2× at 2 cores" was the host, not VT. Fail if
  > 1.5×.

Null control: the 1-core direct rows against the cluster's 1-CPU rows must
show x86 ≈ 2× cheaper per request (smoke: 0.071 vs 0.156 ms at ~5–8k). If
the x86/CM5 ratio is not stable across shapes the platform is confounding
the comparison and only within-platform ratios are read.
