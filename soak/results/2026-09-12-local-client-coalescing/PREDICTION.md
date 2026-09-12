# Pre-registered — client write coalescing (item 1), local x86, 2026-09-12

Nighthawk fork 0649c7b0 adds `--stream-batch-messages N` (N gRPC messages
per DATA frame per stream) and `--stream-batch-flush-interval T`. Same
pinned harness, chart defaults, typed-slot fixture (`reply.jar`), VT, one
connection, 40 streams, realistic tier, ramp 160k–400k; per-thread
user/kernel read at the top step. Runs: N=1 (control, today's behaviour),
N=8 and N=32 with a 0.0005s flush bound. Achieved batch = messages sent /
`stream_batch_flushes`, read rather than assumed.

The item 2 breakdown says the loop's Java time is the WRITE side (the
server's own one-frame-per-response), which client-side coalescing does not
touch; what it can shrink is the server's inbound frame count — the kernel
read share (~0.27 of the loop's 0.94 core) and the 2% frame parse.
- B1 N=8: the loop's kernel share falls by ≥ a third; capacity +5–15%;
  CPU per message −5–10%. B2 N=32 within ±5% of N=8 (diminishing).
- B3 if capacity rises ≥ 30%, the loop's cost was per-frame after all and
  the item 2 attribution of the write side needs re-reading.
- p50 rises by about the batch accumulation time (N / per-stream rate);
  that is the reported cost of batching, not a regression.
Nil below 4%.

## Note (17:17, before batch32 landed): the 0.5 ms flush bound caps the achieved batch
At 40 streams the per-stream rate is 4–10k msg/s, so a 0.5 ms bound
flushes at 2–5 messages per frame whatever N is. `batch32-1conn` will show
the same achieved batch as 8; a fourth row `batch32-5ms-1conn` (flush bound
0.005s, achieved ≥ 16 predicted) is added to test per-frame vs per-message
properly, at the cost of p50 rising toward the bound.
