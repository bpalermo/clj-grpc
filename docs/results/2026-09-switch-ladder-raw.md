# Raw per-step tables — switch ladder, September 2026

Backing data for the switch-ladder entry in [`../soak-results.md`](../soak-results.md);
procedure in [`../../soak/README.md`](../../soak/README.md). **In progress** — runs
are appended per phase as the Nighthawk fork ships each capability.

## Setup

talos-main (arm64, 5 × 4-core workers). Identical 1-CPU/1-Gi Guaranteed pods:
`rest-h1` and `rest-h2c` on worker-04 (same image, one up at a time),
`grpc-jvm` (`EXECUTOR=direct`) on worker-03, the Nighthawk Job on worker-05 as a
1-CPU Guaranteed pod running ONE spinning worker (worker-05 also hosts `pyroscope-0`;
two Guaranteed cores no longer fit there).
Open loop, one `nighthawk_client` per 110 s step, tagged 300 s warmup step
first (a fresh JVM on a 1-CPU quota spends minutes in JIT: 120 s left p99 in seconds). CPU per request from the arm's cgroup `cpu.stat` delta over the step's
delivered count (cAdvisor is not scraped on this cluster).

Request bodies (`//charts:bodies`, `SIZES.txt`):

| tier | JSON | protobuf | JSON/pb |
|---|---|---|---|
| tiny | 16 B | 7 B | 2.29 |
| realistic (target 1,024 B pb) | 1,309 B | 1,025 B | 1.28 |

Client settings, stated because they can move a knee: HTTP/1.1 256
connections; HTTP/2 8 connections × 512 streams, 4,096 in flight (server
`H2C_MAX_STREAMS=1024`; grpc-netty has no per-connection cap — an asymmetry,
disclosed); streams 20 (S) / 40 (E), 256 in flight per stream, 500 ms drain.

Versions: clj-grpc v0.1.6 (arms built from main at the run's chart, 0.2.4), Pedestal 0.8.1 / Jetty 12.0.29, grpc-java 1.83.1 /
Netty 4.2.16.Final, Nighthawk fork `26d79815` (P0) for Phase A; `75d3b4b6` (P1) from Phase B.

## Phase A — transport: `rest-h1` (R1, R5) vs `rest-h2c` (R2, R6)

Run 2026-09-06, chart 0.2.4, Nighthawk P0. Ramp 200→2400 by 200 (tiny),
100→1600 (realistic). Arm restarts during every run: 0. Job logs (gzipped) and the
collector's `tables.md` in `soak/results/2026-09-06-phaseA/`; regenerate a table
with `zcat <log>.gz | soak/collect.sh <mode>`.

**Phase A conclusion.** Switching a Pedestal/Jetty service from HTTP/1.1 to
h2c on the same code buys nothing in capacity (both saturate the core at
~925 rps tiny / ~750 rps realistic), costs 3–16% more CPU per request below
the knee, shaves p99 by 10–30% on the realistic body, and admits ~6–9% more
at the knee. Under overload it is worse: h2c has no flat plateau on 1 KB
bodies because unserved requests sit inside the server instead of failing at
the client. The transport rung of the ladder is not where the gain is.

Two things to know before reading the tables:

- **Past the knee, latency is the client's queue, not the server's.** Open
  loop with `--max-active-requests` means every request the server does not
  take waits at the client up to that cap: 256 for HTTP/1.1 (one per
  connection), 4,096 for h2c. That is why h1 shows ~95 ms p50 at saturation
  and h2c ~4.6 s — the same server, a 16× deeper client queue. Compare the
  arms at and below the knee; above it read only `delivered/s` and `knee/s`.
- **h2c's CPU/heap columns are `n/a` past 1,000 rps** because the arm's
  `/metrics` thread, CPU-throttled behind thousands of queued requests, did not
  answer run.sh's 5 s `curl`. Chart 0.2.5 retries with a 30 s budget. Where
  present, CPU per request is Δ`cpu.stat usage_usec` / delivered for the step.

The first R2 attempt was void (0 delivered on every step): `ladder.sh`
scaled the arms with `kubectl` and the `helm upgrade` that starts the Job
re-applied the chart's replica counts, so the Job ran against a Service with
no endpoints. Fixed by carrying the pairing through Helm; the run below is
the re-run.

### R1 — `rest-h1`, HTTP/1.1, tiny (`nh-rest-h1-http1-tiny-09062027`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 188.6 | 4.08 | 1338.31 | 2259.81 | 11.4 | 3.683 | 77.2 | 63 | 323 |
| 200 | 200.0 | 2.71 | 27.59 | 62.83 | 0.0 | 2.008 | 1.0 | 63 | 325 |
| 400 | 399.6 | 2.45 | 21.71 | 57.61 | 0.3 | 1.562 | 0.3 | 100 | 326 |
| 600 | 598.4 | 2.57 | 72.89 | 378.13 | 1.6 | 1.432 | 2.7 | 101 | 326 |
| 800 | 792.6 | 8.87 | 171.34 | 381.44 | 7.4 | 1.256 | 19.4 | 101 | 324 |
| 1000 | 910.2 | 79.51 | 261.89 | 393.54 | 89.3 | 1.094 | 74.4 | 87 | 324 |
| 1200 | 919.5 | 86.81 | 263.19 | 393.66 | 279.7 | 1.085 | 72.1 | 87 | 324 |
| 1400 | 928.4 | 89.75 | 269.22 | 437.70 | 470.7 | 1.072 | 59.1 | 72 | 324 |
| 1600 | 932.2 | 92.44 | 278.81 | 480.02 | 667.0 | 1.069 | 62.2 | 76 | 324 |
| 1800 | 926.2 | 94.83 | 283.72 | 527.16 | 872.9 | 1.077 | 55.2 | 76 | 324 |
| 2000 | 931.0 | 96.16 | 291.00 | 543.46 | 1068.2 | 1.070 | 77.4 | 82 | 324 |
| 2200 | 918.6 | 97.80 | 303.60 | 592.41 | 1280.5 | 1.084 | 71.9 | 92 | 324 |
| 2400 | 924.6 | 98.43 | 304.79 | 549.81 | 1474.5 | 1.078 | 69.1 | 92 | 324 |

### R2 — `rest-h2c`, HTTP/2 cleartext, tiny (`nh-rest-h2c-http2-tiny-09062128`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 6.69 | 25248.66 | 38449.18 | 0.0 | 3.564 | 100.6 | 62 | 298 |
| 200 | 200.0 | 3.03 | 23.49 | 51.63 | 0.0 | 2.208 | 1.1 | 75 | 299 |
| 400 | 400.0 | 2.81 | 28.14 | 81.04 | 0.0 | 1.780 | 0.2 | 75 | 300 |
| 600 | 600.0 | 2.97 | 53.05 | 120.14 | 0.0 | 1.568 | 2.4 | 73 | 300 |
| 800 | 799.8 | 16.82 | 122.91 | 205.59 | 0.0 | 1.240 | 49.9 | 67 | 301 |
| 1000 | 999.6 | 60.96 | 213.68 | 266.17 | 0.0 | 0.995 | 57.1 | 79 | 302 |
| 1200 | 913.7 | 4686.35 | 6018.56 | 7661.42 | 249.3 | n/a | n/a | 80 | n/a |
| 1400 | 913.1 | 4638.11 | 6767.77 | 7302.02 | 450.0 | n/a | n/a | n/a | n/a |
| 1600 | 916.3 | 4606.92 | 6357.52 | 7705.72 | 646.4 | n/a | n/a | n/a | n/a |
| 1800 | 954.0 | 4547.94 | 8541.96 | 9796.85 | 808.8 | n/a | n/a | 92 | n/a |
| 2000 | 939.7 | 4600.63 | 5629.54 | 7729.84 | 1023.0 | n/a | n/a | n/a | n/a |
| 2200 | 915.3 | 4580.97 | 6508.51 | 6920.60 | 1247.4 | n/a | n/a | n/a | n/a |
| 2400 | 930.6 | 4503.11 | 5757.73 | 9715.06 | 1432.1 | n/a | n/a | n/a | n/a |

### R5 — `rest-h1`, HTTP/1.1, realistic (`nh-rest-h1-http1-realistic-09062158`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 180.7 | 26.66 | 1590.69 | 2710.57 | 19.3 | 4.584 | 252.7 | 117 | 335 |
| 100 | 100.0 | 3.42 | 16.29 | 61.13 | 0.0 | 2.494 | 0.5 | 117 | 336 |
| 200 | 199.8 | 3.06 | 26.66 | 257.26 | 0.2 | 2.091 | 0.9 | 107 | 336 |
| 300 | 300.0 | 2.89 | 25.73 | 72.04 | 0.0 | 1.843 | 0.3 | 83 | 337 |
| 400 | 399.9 | 2.98 | 64.67 | 124.73 | 0.1 | 1.792 | 7.4 | 115 | 338 |
| 500 | 499.0 | 3.04 | 88.42 | 328.50 | 1.0 | 1.683 | 5.8 | 115 | 338 |
| 600 | 597.8 | 3.49 | 133.57 | 378.37 | 2.2 | 1.593 | 11.0 | 113 | 338 |
| 800 | 754.7 | 100.80 | 334.74 | 597.00 | 44.6 | 1.319 | 143.9 | 105 | 338 |
| 1000 | 754.4 | 110.79 | 346.73 | 600.77 | 244.7 | 1.320 | 134.9 | 105 | 338 |
| 1200 | 730.2 | 116.63 | 401.85 | 718.73 | 469.0 | 1.363 | 122.9 | 91 | 338 |
| 1400 | 762.3 | 114.22 | 348.49 | 678.63 | 636.7 | 1.310 | 125.6 | 124 | 338 |
| 1600 | 761.2 | 114.85 | 375.16 | 680.13 | 837.9 | 1.311 | 135.1 | 124 | 338 |

### R6 — `rest-h2c`, HTTP/2 cleartext, realistic (`nh-rest-h2c-http2-realistic-09062226`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 197.3 | 222.71 | 37604.03 | 53146.03 | 2.6 | 4.179 | 91.3 | 67 | 308 |
| 100 | 100.0 | 3.65 | 13.40 | 28.76 | 0.0 | 2.799 | 0.3 | 67 | 308 |
| 200 | 200.0 | 3.24 | 15.27 | 36.86 | 0.0 | 2.337 | 0.0 | 91 | 308 |
| 300 | 300.0 | 3.08 | 21.29 | 50.75 | 0.0 | 2.126 | 0.3 | 91 | 309 |
| 400 | 400.0 | 3.08 | 54.39 | 127.17 | 0.0 | 1.969 | 0.8 | 79 | 309 |
| 500 | 500.0 | 3.15 | 61.21 | 132.84 | 0.0 | 1.839 | 1.5 | 95 | 309 |
| 600 | 600.0 | 6.17 | 107.54 | 183.39 | 0.0 | 1.654 | 18.7 | 95 | 310 |
| 800 | 799.6 | 62.61 | 315.79 | 387.37 | 0.0 | 1.244 | 38.6 | 95 | 310 |
| 1000 | 733.4 | 5800.98 | 9978.25 | 14963.70 | 229.5 | n/a | n/a | 69 | n/a |
| 1200 | 516.7 | 5734.14 | 39699.09 | 40294.68 | 636.7 | n/a | n/a | n/a | n/a |
| 1400 | 544.2 | 8009.55 | 18418.24 | 20702.04 | 818.5 | n/a | n/a | n/a | n/a |
| 1600 | 656.9 | 5801.51 | 7282.88 | 8109.42 | 905.8 | n/a | n/a | n/a | n/a |

Nighthawk's global counters for the 1,200 step, h2c vs h1 (110 s):

| | h2c | h1 |
|---|---|---|
| `http_2xx` | 56,842 | 80,327 |
| `http_5xx` | 276 | 0 |
| `stream_resets` (server `RST_STREAM`) | 750 | — |
| `pool_overflow` (never sent) | 70,035 | 51,589 |

### Phase A, realistic tier (1.3 KB JSON) — what the switch buys

| | h1 | h2c |
|---|---|---|
| knee (last step with `knee/s` ≈ 0 and p50 < 10 ms) | 600 | 600 |
| delivered at 800 offered | 755 | 800 |
| plateau (delivered/s, steps ≥ 1,000) | ~750 | 517–733, falling |
| p50 / p99 at 300 offered (ms) | 2.89 / 25.7 | 3.08 / 21.3 |
| p50 / p99 at 500 offered (ms) | 3.04 / 88.4 | 3.15 / 61.2 |
| CPU per request at 300 / 600 offered (ms) | 1.84 / 1.59 | 2.13 / 1.65 |
| RSS (MB) | 338 | ~310 |

Same shape as the tiny tier below the knee, now with the body big enough to
matter: both arms knee at 600 and h2c delivers all of 800 where h1 sheds 6%,
at a slightly higher CPU cost per request (+3–16%) and a slightly better p99.
Above the knee the two diverge in h1's favour, and this is the Phase A
finding that changes a decision: **h2c on this server has no graceful
plateau on 1 KB bodies.** With 8 connections × 512 streams parked at the
client and `H2C_MAX_STREAMS=1024` at the server, Jetty carries thousands of
in-flight requests it cannot serve; at 1,200 offered it delivers 517/s
against h1's 730/s on the same core, answers 276 with 5xx and resets 750
streams, and p99 reaches 40 s. HTTP/1.1's plateau is flat because its
overload is rejected at connection setup, before any server work; h2c's is
absorbed into the server, where it costs CPU and heap. Tiny bodies do not
show this (R2 held ~925/s throughout), so it is the per-stream buffering, not
the framing. Bound it with a client cap (`--max-active-requests` near the
plateau) or server admission control; neither exists in the REST arm today.

### Phase A, tiny tier — what the switch buys

| | h1 | h2c |
|---|---|---|
| plateau (delivered/s, mean of steps ≥ 1,200) | ~925 | ~925 |
| delivered at 1,000 offered | 910 | 1,000 |
| p50 / p99 at 400 offered (ms) | 2.45 / 21.7 | 2.81 / 28.1 |
| p50 / p99 at 800 offered (ms) | 8.9 / 171 | 16.8 / 123 |
| CPU per request at 400 / 1,000 offered (ms) | 1.56 / 1.09 | 1.78 / 1.00 |
| RSS (MB) | 324 | ~300 |

On 7-byte bodies the transport switch is worth nothing in capacity: both
arms saturate the core at ~925 rps and CPU per request converges to ~1.0 ms
either side of the knee. Below the knee h2c costs slightly more per request
and is slightly slower at p50 — HTTP/2 framing overhead on a body too small
to amortize it. The one difference in its favour is admission at the edge:
at 1,000 offered h2c delivers all 1,000 where h1 already sheds 9%, because
multiplexing keeps 4,096 requests parked instead of failing connection
attempts. Validation against August: R1's ~930 plateau and its knee at 1,000
match the k6 measurement (~960, queue-death at 1,000) within the two
instruments' difference in accounting.

## Phase B — protocol: `rest-h2c` vs `grpc-jvm` unary (R3, R7)

_pending P1 (gRPC unary)_

## Phase C — interaction model: unary vs `grpc-jvm` stream (R4, R8)

_pending P2 (bidi streaming); cross-checked against the Clojure driver_
