# Client write coalescing — Nighthawk fork 0649c7b0, local x86, 2026-09-12

Ranking item 1. The fork's `--stream-batch-messages N` writes N gRPC
messages per HTTP/2 DATA frame per stream, with
`--stream-batch-flush-interval T` bounding how long a partial batch waits;
messages are stamped when queued, so the wait lands in p50 honestly.
Same pinned harness, chart defaults, typed-slot fixture, VT, one
connection, 40 streams, realistic tier, ramp 160k–400k; the loop's
user/kernel split at each top step. Achieved batch = messages sent /
`stream_batch_flushes`, read, not assumed. `PREDICTION.md` first, with the
flush-bound cap noted before the third row landed.

| run | achieved msgs/frame at 400k | delivered at 400k | µs/msg at 400k (Δ vs control) | mid-ramp Δ | loop core (user + sys) | p50 at 400k |
|---|---|---|---|---|---|---|
| batch 1 (control, today's driver) | 1.0 | ~383k | 9.03 | — | 0.96 (0.69 + 0.27) | 0.5 ms* |
| batch 8, 0.5 ms bound | 5.3 | ~386k | 8.95 (−1%) | −11 to −12% | 0.94 (0.64 + 0.30) | 0.9 ms |
| batch 32, 0.5 ms bound | 5.6 | ~392k | 8.72 (−3%) | −12 to −13% | 0.94 (0.66 + 0.28) | 0.8 ms |
| batch 32, 5 ms bound | 25 | ~396k | **8.20 (−9%)** | −12 to −18% | **0.88 (0.61 + 0.27)** | 3–4.6 ms |

*\* control p50 from the collector's table.*

Reading: 25 messages per inbound frame instead of one takes ~0.08 core off
the loop — all of it user time; the kernel share does not move — and ~9%
off the message at the top, for milliseconds of added latency. So the
loop's ~2.5 µs per message is **per message, not per frame**: the inbound
frame count was worth ~10–15% of it, and the rest is the server's own
one-frame-per-response write side and buffer bookkeeping, as the item 2
breakdown said. B1's "kernel share falls by a third" failed — the kernel
time is the writes' `writev`, not the reads. Client coalescing is a
latency-for-CPU trade worth ~9% at best; it is not a lever to recommend
over batching at the message level, which pays the loop once for N on
both sides. Two things to carry forward: the experiment bounds what any
client-side change can do for the server's loop at roughly a tenth — the
other nine tenths are the server's own write side; and
`--stream-batch-messages` is a ceiling, the flush interval sets the
achieved batch at these per-stream rates, so read `stream_batch_flushes`
and never N off the command line.
