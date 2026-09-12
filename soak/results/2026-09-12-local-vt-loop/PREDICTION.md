# Pre-registered — where the VT loop's time goes (item 2), and two connections under the defaults (item 3) — local x86, 2026-09-12

Same pinned harness, chart defaults (`-Xmn256m`, `INBOUND_CREDITS=8`), the
typed-slot fixture (main after #102), VT, 4 server cores, streaming, 40
streams, realistic tier. Per-thread read of the server JVM over 20 s at the
top step (`/proc`, `harness/thread-at-top.sh`) is the readout for item 2.

## Item 2 — the write side's share of the loop (one connection)
`REPLY=full` (the arm: decode + encode the 1 KB echo), `REPLY=tiny` (decode,
answer a one-field reply), `REPLY=none` (decode, answer nothing; delivered
is then messages SENT by the driver). Expectations at the top step:
- W1 `tiny`: loop share falls from ~97% by the response encode's size
  effect only — predicted a few points; capacity +5–15%.
- W2 `none`: no response write at all. If the loop's 97% is mostly the
  read side (frame decode, deframe, hand-off), the loop stays ≥85% and
  capacity rises < 20%; if the write path is a large share, the loop drops
  below 70% and capacity rises ≥ 40%. Prediction: read side dominates —
  loop ≥ 80%, capacity +15–30%.

## Item 3 — one vs two connections, defaults + `:worker-threads 2`
Before the defaults, every added connection cost VT throughput (one 237k,
two 203k). With the loops and GC fixed:
- C1 two connections ≥ one connection × 1.15 (the second loop carries a
  second connection's read side) — then the VT rule becomes "one or two";
  if two is within ±5% of one, the rule stays "one".
Nil below 4%.

## Note (15:30): `REPLY=none` is not measurable with this driver
Without responses the client's per-stream in-flight cap (256 × 40) fills at
once and the sequencer stops sending: 168 msg/s. The row is void. The write
path's share is read instead from a JFR execution-sample profile of the
server at the top step (`jfr-1conn`, `REPLY=full`, 60 s recording from
t=300 s): the loop thread's stacks split into frame decode / deframe /
hand-off vs write/flush; the carriers' into decode / handler / encode.
Prediction W2 restated: the loop's samples are ≥ 60% read-side.

## Note (15:55): #103 is not a regression; `reply-full-1conn` was an outlier
Same fixture and defaults before/after clj-grpc #103 (outbound flow
control): 9.28 vs 9.04 µs per message, 353k vs 380k delivered at 400k, loop
94% vs 96%. The first `reply-full-1conn` row (320k at 9.87 µs, loop 85%,
the first run after a jar build on a host just released by a peer) is the
outlier and is not read; `post103-1conn` is the full-reply baseline for the
item 2 split against `reply-tiny-1conn`.
