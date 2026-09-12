# Virtual-thread streaming levers — local x86, 2026-09-12

What makes gRPC streaming on the virtual-thread executor faster, measured one
lever at a time and then stacked. Same pinned harness as the two local
campaigns before it (server on physical cores 2–5 with a 4-CPU quota and a
1 GB limit, driver on 6–9, loopback, same bodies, same Nighthawk fork,
`run.sh`/`collect.sh` unchanged), streaming 40 streams on one connection (one
worker) and on eight (`--max-concurrent-streams 5`, four workers), ramp
160k–400k (ceiling runs 400k–640k), same-session baseline. Predictions for
every phase were written before its first run (`PREDICTION.md`, seven
phases, one mid-course correction recorded there). `harness/` holds the
runners and `harness/server-experiment.patch` the two env-gated experiment
changes to clj-grpc (`REQUEST_BATCH=n`, `CALL_EXECUTOR=percall`) that
`exp.jar` was built from; `app.jar` is the unmodified deploy jar.

**The baseline JVM runs Serial GC.** Under a 1 GB limit JDK 21 (and 25) is
not "server-class" and picks SerialGC with a young generation that starts at
~5 MB — verified with `-XX:+PrintFlagsFinal` in the same container shape.
The cluster's 1-CPU arms are the same shape.

## Every run — peak, cost, memory, against the same-session baseline

| run | peak msg/s | ms/msg | RSS MB | vs baseline |
|---|---|---|---|---|
| base-1conn | 245,044 | 0.013 | 237 | +0% |
| jdk25-1conn | 215,596 | 0.014 | 234 | -12% |
| loops2-1conn | 242,480 | 0.013 | 205 | -1% |
| pargc-1conn | 301,501 | 0.012 | 411 | +23% |
| batch32-1conn | 304,637 | 0.010 | 275 | +24% |
| base-8conn | 166,452 | 0.019 | 268 | +0% |
| jdk25-8conn | 216,726 | 0.018 | 727 | +30% |
| loops2-8conn | 189,020 | 0.017 | 227 | +14% |
| pargc-8conn | 221,665 | 0.018 | 516 | +33% |
| batch32-8conn | 236,436* | 0.013 | 257 | +42% |
| percall-1conn | 236,847* | 0.013 | 228 | -3% |
| percall-8conn | 179,167 | 0.018 | 267 | +8% |
| batch8-1conn | 302,031 | 0.010 | 205 | +23% |
| batch128-1conn | 303,553 | 0.010 | 236 | +24% |
| serialyoung-1conn | 304,948 | 0.012 | 453 | +24% |
| g1-1conn | 276,758 | 0.013 | 370 | +13% |
| zgc-1conn | 262,290 | 0.014 | 778 | +7% |
| pargcyoung-1conn | 310,902 | 0.012 | 487 | +27% |
| direct-pargc-8conn | 399,855* | 0.010 | 427 | +140% |
| combo-1conn | 396,313* | 0.009 | 410 | +62% |
| combo-8conn | 329,318 | 0.012 | 567 | +98% |
| combo-loops2-8conn | 397,523* | 0.010 | 438 | +139% |
| combo-percall-1conn | 396,359* | 0.009 | 415 | +62% |
| direct-pargc-8conn-high | 487,380 | 0.008 | 475 | +193% |
| direct-serialyoung-8conn | 468,077 | 0.008 | 473 | +181% |
| combo-1conn-high | 448,962 | 0.008 | 422 | +83% |
| combo-loops2-8conn-high | 414,086 | 0.009 | 574 | +149% |

* delivered everything offered at its peak step (ramp-limited; a floor)

## What it says

| lever | one connection (base ~240k at 0.013 ms) | eight connections (base ~166k at 0.020) | cost |
|---|---|---|---|
| **young generation** (`-Xmn256m` on Serial, or ParallelGC, or both) | ~303k, +25% | ~221k, +33% | +170–280 MB RSS |
| **batched inbound credits** (`REQUEST_BATCH` 8 = 32 = 128) | ~303k at 0.010, +27%, −23% CPU/msg | ~230k at 0.013, +40%, −35% CPU/msg | none |
| two event loops instead of eight | nil | ~189k, +14% | none |
| per-call long-lived virtual thread | nil | +7% | complexity |
| G1 / generational ZGC | +15% / +6% | — | +130 / +540 MB RSS |
| JDK 25 runtime | −12% | +30%, RSS 727 MB | unexplained; not a lever |
| **stacked** (young gen + credits [+ two loops]) | **~445k at 0.008–0.009** (server 95%, driver worker at its limit) | **~414k at 0.009**, CPUs 98% | |
| `:direct`, eight connections, for scale | Serial default ~278k → young gen **~468–487k at 0.008** | | |

- The GC lever is the young generation's size, not the collector: Serial
  with `-Xmn256m`, ParallelGC, and both give the same ~303k. G1 and ZGC are
  worse than a sized Serial. The `:direct` "2.8-core plateau" the previous
  campaign left unresolved was this — with a young generation it delivers
  ~487k on 3.9 cores.
- Batched credits remove the per-message `request(1)` hop from the handler's
  thread back to the event loop; the batch size does not matter above a
  handful. It is the one lever that lowers CPU per message rather than
  removing stalls.
- Stacked, the virtual-thread executor reaches 85–90% of tuned `:direct` on
  the same cores, from 60–65% at the defaults, and its single-connection
  ceiling (~445k) is now above `:direct`'s untuned eight-connection one.
