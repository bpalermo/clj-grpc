# Where the virtual-thread loop's time goes, and the two-connection rule — local x86, 2026-09-12

Ranking items 2 and 3 after the shipped defaults. Same pinned harness
(server cores 2–5, driver 6–9, loopback), chart defaults (`-Xmn256m`,
`INBOUND_CREDITS=8`), the typed-slot fixture (main after #102, one jar for
every row), VT, 4 server cores, streaming, 40 streams, realistic tier.
`PREDICTION.md` first, with the voided rows named. CPU per message is the
arm's cgroup counter over messages at full precision (`harness/cpu5.py`).

## Item 2 — the loop's per-message time, one connection at ~372k msg/s

Three instruments on the same plateau:

| instrument | what it says |
|---|---|
| per-thread user/kernel split (`usersys-at-top.txt`) | loop **0.94 core = 0.67 user + 0.27 kernel**; four carriers 0.61 each, all user |
| JFR execution samples, loop thread (`loop.jfr.gz`, `harness/jfrparse.py`) | write path ~49% (HTTP/2 encoder, promises, outbound buffer, iov assembly), buffer refcount/pool/recycler ~41% (mostly write-side and freeing read buffers), flow-control 7%, inbound frame decode 2% |
| reply size (`post103-1conn` full 1 KB echo vs `reply-tiny-1conn` one-field reply) | 9.04 → 7.06 µs per message; the 1 KB response costs the carriers ~1.7 µs (encode) and the loop ~0.2 µs |

So the loop's ~2.5 µs per message is **~1.8 µs of Java write-path
machinery and buffer bookkeeping plus ~0.7 µs of kernel I/O**, and it is
almost independent of the bytes in the message. The read side barely
touches the loop in Java: grpc-java deframes on the application thread, so
inbound work on the loop is the kernel read and a thin frame parse. The
lever that follows is message count, not message size: a service that
batches N items into one streamed message pays the loop once for N.
`REPLY=none` could not be measured — without responses the client's
in-flight cap fills and the driver stops (168 msg/s).

## Item 3 — one vs two connections, defaults + `:worker-threads 2`

`conn1-loops2` ~358k at 9.38 µs vs `conn2-loops2` ~377k at 9.34 µs at
400k offered (+5%, at the replicate floor), +8% per message below the knee.
Before the defaults a second connection cost VT 14%; now it costs nothing
and gains nothing. The rule stays "one connection is enough"; two no longer
hurts. Prediction (≥ +15%) failed.

## Also settled here

- clj-grpc #103 (outbound flow control) is not a regression: same fixture
  and defaults before/after, 9.28 vs 9.04 µs per message, loop 94% vs 96%.
  The first `reply-full-1conn` row (320k, loop 85%) was an outlier and is
  not read.
- The JFR's first attempt was empty: a recording window that ends after
  the run does, plus a forced container stop, discards it. The harness now
  records at 240–300 s and stops the container gracefully.
