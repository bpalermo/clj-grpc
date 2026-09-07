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
collector's `tables.md` in `soak/results/2026-09-06-phase{A,B}/`; regenerate a table
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

Run 2026-09-06/07, chart 0.2.5, Nighthawk P1 (`75d3b4b6`): `--grpc`, raw
`HelloRequest` bytes from `--request-body-file`, scored on `grpc-status`
(`benchmark.grpc_status.0` is the ok counter, `latency_grpc_ok` the
histogram). Client settings identical to the h2c arm: 8 connections, 4,096 in
flight, 300 s warmup at 200 rps, 110 s steps. Ramps extended past August's
range after the fork's acceptance run found no knee at 2,400.

### R3 — `grpc-jvm` unary, tiny (`nh-grpc-jvm-grpc-unary-tiny-09062341`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.67 | 1080.10 | 1914.63 | 0.0 | 1.094 | 1.1 | 17 | 152 |
| 400 | 400.0 | 1.44 | 9.58 | 26.64 | 0.0 | 0.507 | 0.2 | 16 | 152 |
| 800 | 800.0 | 1.44 | 11.40 | 25.59 | 0.0 | 0.381 | 0.0 | 18 | 152 |
| 1200 | 1200.0 | 1.39 | 28.58 | 100.79 | 0.0 | 0.326 | 0.0 | 19 | 153 |
| 1600 | 1600.0 | 1.41 | 22.14 | 64.59 | 0.0 | 0.289 | 0.1 | 18 | 153 |
| 2000 | 2000.0 | 1.48 | 22.65 | 45.64 | 0.0 | 0.260 | 0.0 | 18 | 154 |
| 2400 | 2399.9 | 1.53 | 23.83 | 54.81 | 0.0 | 0.233 | 0.0 | 16 | 154 |
| 2800 | 2799.9 | 1.66 | 35.61 | 79.73 | 0.1 | 0.212 | 0.1 | 22 | 155 |
| 3200 | 3199.9 | 1.70 | 42.30 | 98.57 | 0.0 | 0.190 | 0.0 | 23 | 156 |
| 3600 | 3592.2 | 1.82 | 54.97 | 411.01 | 7.7 | 0.176 | 0.0 | 26 | 159 |
| 4000 | 3996.3 | 1.97 | 63.65 | 126.08 | 3.7 | 0.160 | 0.0 | 26 | 159 |
| 4400 | 4399.8 | 2.04 | 70.07 | 172.89 | 0.1 | 0.149 | 0.0 | 25 | 160 |
| 4800 | 4799.8 | 2.21 | 80.66 | 196.46 | 0.1 | 0.138 | 0.0 | 25 | 160 |

No knee. Every step delivered its offered rate, throttling stayed at zero,
and CPU per request kept falling with rate (0.51 ms at 400 → 0.14 ms at
4,800: the event loop's fixed cost amortizing), so at 4,800 rps the arm was
using about two-thirds of its core. August's "~2,140 rps knee" for this arm
was the closed-loop k6 driver, not the server. A follow-up run extends the
ramp to 8,000 to find the real one; the 1-worker Nighthawk driver may cap
first, which will show as `knee/s` growing with a flat arm CPU.

### R7 — `grpc-jvm` unary, realistic (`nh-grpc-jvm-grpc-unary-realistic-09070010`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.88 | 6146.23 | 7216.30 | 0.0 | 1.267 | 14.2 | 19 | 150 |
| 200 | 200.0 | 1.76 | 9.79 | 27.40 | 0.0 | 0.778 | 0.0 | 19 | 151 |
| 400 | 400.0 | 1.61 | 10.01 | 20.69 | 0.0 | 0.618 | 0.0 | 20 | 151 |
| 600 | 600.0 | 1.59 | 13.72 | 28.03 | 0.0 | 0.555 | 0.1 | 20 | 152 |
| 800 | 800.0 | 1.72 | 36.83 | 102.80 | 0.0 | 0.519 | 0.4 | 19 | 152 |
| 1000 | 1000.0 | 1.60 | 20.86 | 46.98 | 0.0 | 0.463 | 0.0 | 19 | 153 |
| 1200 | 1200.0 | 1.66 | 36.60 | 159.61 | 0.0 | 0.436 | 0.0 | 22 | 153 |
| 1400 | 1400.0 | 1.82 | 37.69 | 89.83 | 0.0 | 0.413 | 0.2 | 23 | 154 |
| 1600 | 1599.9 | 1.85 | 59.17 | 312.90 | 0.1 | 0.399 | 0.3 | 23 | 154 |
| 2000 | 1999.8 | 2.32 | 95.36 | 346.60 | 0.1 | 0.347 | 0.4 | 24 | 156 |
| 2400 | 2386.4 | 4.14 | 2635.33 | 5526.26 | 13.5 | 0.331 | 13.3 | 35 | 186 |
| 2800 | 2799.7 | 3.28 | 219.32 | 285.05 | 0.1 | 0.274 | 0.1 | 35 | 186 |
| 3200 | 3197.7 | 4.29 | 1096.88 | 2342.39 | 2.1 | 0.251 | 2.1 | 36 | 191 |

Delivered in full through 3,200 rps. The quota first shows at the top: 2 s
throttled and p99 1.1 s at 3,200 with CPU per request at 0.25 ms (~0.8 of
the core). The 2,400 step is an outlier (p99 2.6 s, 13 s throttled, heap and
RSS stepping up 24→35 MB / 156→186 MB, the step after it clean) — a one-off
JIT recompilation or GC event under load rather than the knee, since 2,800
delivered cleanly at lower cost. A follow-up run extends the ramp to 5,200.

### R3b — `grpc-jvm` unary, tiny, 5,200→8,000 (`nh-grpc-jvm-grpc-unary-tiny-09070039`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.66 | 1462.57 | 2302.15 | 0.0 | 1.085 | 9.6 | 19 | 144 |
| 5200 | 5199.4 | 2.48 | 578.55 | 832.67 | 0.5 | 0.135 | 4.7 | 30 | 167 |
| 5600 | 5599.5 | 2.44 | 94.54 | 163.75 | 0.4 | 0.125 | 0.6 | 40 | 168 |
| 6000 | 5999.7 | 2.61 | 66.04 | 141.22 | 0.2 | 0.118 | 0.0 | 40 | 169 |
| 6400 | 6391.3 | 3.24 | 531.82 | 894.40 | 7.7 | 0.110 | 0.4 | 39 | 170 |
| 6800 | 6791.2 | 2.94 | 99.79 | 174.40 | 7.5 | 0.107 | 0.0 | 25 | 170 |
| 7200 | 7191.4 | 3.30 | 301.86 | 544.87 | 8.5 | 0.103 | 0.4 | 36 | 170 |
| 7600 | 7599.1 | 3.43 | 125.52 | 235.95 | 0.7 | 0.099 | 0.2 | 36 | 170 |
| 8000 | 7976.6 | 3.80 | 122.42 | 272.88 | 23.2 | 0.095 | 0.2 | 41 | 170 |

Still no knee on the arm: CPU per request keeps falling to 0.095 ms, so at
8,000 rps the server uses ~0.76 of its core and throttling stays under a
second per step. What moves here is the driver. `knee/s` (sends that found
all 4,096 slots busy) is sporadic rather than climbing, and the p99 spikes at
5,200 / 6,400 / 7,200 come and go without a matching change on the arm — the
signature of one spinning Nighthawk worker on a 1-CPU quota losing its
schedule, not of the server queueing. Nighthawk opened 4 connections for the
run, not the 8 configured. The tiny tier's capacity is therefore a lower
bound, **> 8,000 rps per core**, which is where a single-worker driver on
this cluster stops being able to look. (A 2-worker driver does not schedule
on worker-05 next to `pyroscope-0`; running it elsewhere would put the driver
on an arm's node.)

### R7b — `grpc-jvm` unary, realistic, 3,600→5,200 (`nh-grpc-jvm-grpc-unary-realistic-09070100`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.88 | 6385.04 | 7273.97 | 0.0 | 1.307 | 25.3 | 21 | 162 |
| 3600 | 3175.5 | 19.08 | 4032.17 | 15589.70 | 422.3 | 0.282 | 39.8 | 44 | 194 |
| 4000 | 3999.2 | 10.37 | 781.65 | 1199.96 | 0.4 | 0.216 | 6.6 | 44 | 194 |
| 4400 | 4366.6 | 41.93 | 1300.96 | 3717.99 | 31.3 | 0.203 | 7.6 | 31 | 198 |
| 4800 | 4674.7 | 249.57 | 1666.58 | 2864.97 | 125.2 | 0.203 | 24.3 | 42 | 200 |
| 5200 | 4458.1 | 756.22 | 1563.75 | 3609.46 | 707.7 | 0.221 | 44.5 | 42 | 198 |

The knee, found: 4,000 rps delivers in full at 0.86 of the core (6.6 s
throttled, p50 10 ms); 4,400 sheds 1%; 4,800 delivers 4,675 with p50 at
250 ms; 5,200 is saturation (44 s throttled, 708/s never sent, delivered
falls to 4,458). Plateau ~4,500–4,700 delivered. The 3,600 step is the
fresh pod's first step after a 200 rps warmup and its 40 s of throttling and
p50 19 ms are a JIT event at the jump, the same outlier shape as R7's 2,400
step; read 4,000 as the clean floor of this run.

### Phase B — what the protocol switch buys (`rest-h2c` → `grpc-jvm` unary)

Same service contract (echo of the same nested `Payload`), same core, same
client settings (8 connections, 4,096 in flight); the switch replaces
Pedestal/Jetty/JSON with grpc-netty/protobuf and the body shrinks 1.28×
(JSON 1,309 B → pb 1,025 B). Against **both** REST arms, since h1 is what
existing services run and h2c is the rung just below:

| | rest-h1 | rest-h2c | grpc-jvm unary | switch buys |
|---|---|---|---|---|
| **tiny** plateau, delivered/s | ~925 | ~925 | **> 8,000** (driver-bound; arm at 0.76 core) | > 8.6× |
| tiny CPU/req at 800 offered | 1.26 ms | 1.24 ms | 0.38 ms | 3.3× cheaper |
| tiny p50 / p99 at 800 (ms) | 8.9 / 171 | 16.8 / 123 | 1.4 / 11 | |
| tiny p50 / p99 at 400 (ms) | 2.45 / 21.7 | 2.81 / 28.1 | 1.44 / 9.6 | |
| **realistic** knee (full delivery, p50 < 10 ms) | 600 | 600 | **4,000** | 6.7× |
| realistic plateau, delivered/s | ~750 | collapses (517) | ~4,600 | 6.1× |
| realistic CPU/req at 600 offered | 1.59 ms | 1.65 ms | 0.56 ms | 2.9× cheaper |
| realistic p50 / p99 at 600 (ms) | 3.49 / 134 | 6.17 / 108 | 1.59 / 13.7 | |
| RSS at plateau (MB) | 338 | ~310 | ~195 | |
| heap at plateau (MB) | ~110 | ~95 | ~40 | |

Three things the numbers say:

- **This rung is where the gain is.** Per core, the protocol switch is worth
  6× capacity on a 1 KB body and more than 8× on a tiny one, with CPU per
  request 3× lower at the same offered rate and p99 an order of magnitude
  lower below REST's knee. h1 → h2c was worth nothing; h2c → gRPC is worth
  everything the August comparison attributed to "gRPC", and more, now that
  a single instrument measures both sides.
- **Overload is graceful again.** Under the same 4,096-deep client queue
  that collapsed h2c, grpc-netty degrades to a plateau (5,200 offered →
  4,458 delivered, every response `grpc-status 0`, zero errors): the work it
  cannot serve costs it ~5% of goodput, not 30%. The difference is where the
  unserved requests wait — Netty's event loop and HTTP/2 flow control keep
  them in the socket buffers, Jetty's thread pool pulls them in.
- **August under-measured gRPC by 2–4×.** The k6 closed-loop "knee" at
  ~2,140 rps was the driver. The server's real unary capacity per core is
  ~4,600 rps on realistic bodies and beyond 8,000 on tiny ones, which also
  moves the August streaming-vs-unary ratio (7.5×) down toward 2–3× before
  Phase C measures it directly.

Disclosures: CPU per request is the arm's cgroup delta over delivered
responses, so it includes the kernel's share of the arm's socket work; the
tiny-tier ceiling is the driver's, not the arm's; per-step JIT outliers
(R7 2,400, R7b 3,600) are visible in the tables and excluded from the
readings; grpc-netty has no per-connection stream cap where Jetty has
`H2C_MAX_STREAMS=1024` — with 8 connections × 512 client streams neither cap
bound these runs.

## Phase C — interaction model: unary vs `grpc-jvm` stream (R4, R8)

_pending P2 (bidi streaming); cross-checked against the Clojure driver_
