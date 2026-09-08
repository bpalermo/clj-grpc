# Raw per-step tables — switch ladder, September 2026

Backing data for the switch-ladder entry in [`../soak-results.md`](../soak-results.md);
procedure in [`../../soak/README.md`](../../soak/README.md). All three phases
ran 2026-09-06/07; the ladder summary is at the end.

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
Netty 4.2.16.Final, Nighthawk fork `26d79815` (P0) for Phase A, `75d3b4b6` (P1) for Phase B, `50dce0eb` (P2) for Phase C.

## Phase A — transport: `rest-h1` (R1, R5) vs `rest-h2c` (R2, R6)

Run 2026-09-06, chart 0.2.4, Nighthawk P0. Ramp 200→2400 by 200 (tiny),
100→1600 (realistic). Arm restarts during every run: 0. Job logs (gzipped) and the
collector's `tables.md` in `soak/results/2026-09-06-phase{A,B}/` and `2026-09-07-phaseC/`; regenerate a table
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
ramp to 8,000 (R3b), and a two-worker cross-check by the fork session
places the knee at ~10,000–11,000.

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

Still no knee on the arm at 8,000: CPU per request keeps falling to
0.095 ms, so the server uses ~0.76 of its core and throttling stays under a
second per step. The p99 spikes at 5,200 / 6,400 / 7,200 come and go without
a matching change on the arm. Nighthawk opened 4 connections for the run,
not the 8 configured: for HTTP/2 `--connections` is a cap, and the pool
adds connections only as stream demand requires (fork session's reading).

**Cross-check with a two-worker driver** (the fork session, same P1 image,
concurrency 2, 2,048 in flight per worker, 30 s steps, no cgroup counters):

| offered | delivered/s | `grpc_ok` | `pool_overflow` | p50 | p99 |
|---|---|---|---|---|---|
| 8,000 | 7,997 | 239,916 | 20 | 6.6 ms | 131 ms |
| 12,000 | 10,704 | 321,132 | 36,531 (10%) | 150 ms | 634 ms |
| 16,000 | 9,642 | 289,249 | 187,450 (44%) | 366 ms | 714 ms |

Two workers deliver the same 8,000 as one, so the single spinning worker was
not the limit there; both sequencers kept 100% of their schedule at 12k and
16k, so the shortfall past 8,000 is in-flight overflow waiting on the arm.
**Tiny-tier knee: ~10,000–11,000 rps per core**, with delivered throughput
falling past it (9.6k at 16k offered) — the same shape as the realistic
tier at 5,200. The authoritative per-step CPU numbers stop at 8,000 (this
run); the knee position is the cross-check's.

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
| **tiny** knee / plateau, delivered/s | 1,000 / ~925 | 1,000 / ~925 | **~10,500** / ~10,700 (2-worker cross-check) | ~11× |
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
  6× capacity on a 1 KB body and ~11× on a tiny one, with CPU per
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
  ~4,600 rps on realistic bodies and ~10,500 on tiny ones, which also
  moves the August streaming-vs-unary ratio (7.5×) down toward 2–3× before
  Phase C measures it directly.

Disclosures: CPU per request is the arm's cgroup delta over delivered
responses, so it includes the kernel's share of the arm's socket work; the
tiny-tier knee comes from the fork session's two-worker cross-check (30 s
steps, no cgroup counters), the ladder's own tables stop at 8,000; per-step JIT outliers
(R7 2,400, R7b 3,600) are visible in the tables and excluded from the
readings; grpc-netty has no per-connection stream cap where Jetty has
`H2C_MAX_STREAMS=1024` — with 8 connections × 512 client streams neither cap
bound these runs.

## Phase C — interaction model: unary vs `grpc-jvm` stream (R4, R8)

Run 2026-09-07, chart 0.2.6, Nighthawk P2 (`50dce0eb`): `--grpc-stream`
opens N persistent bidi streams to `Greeter/Chat` before the step starts,
schedules `--rps` messages per second in aggregate across them, and
measures each message send→echo (`benchmark_stream.message_latency`).
`delivered/s` is `stream_messages_received` over the step; `knee/s` is
`stream_deferred` (sends that found the stream's 256 in-flight slots busy).
Every stream must close `grpc-status 0` with no resets, and sends must match
the schedule (else the step is flagged client-limited): no step below
tripped either check. All streams multiplex on one HTTP/2 connection
(Nighthawk's `--connections` is a cap). Same warmup, steps and arm as
Phase B; the arm's `Chat` handler echoes the same payload the unary
`SayHello` does.

### R4 — 20 streams, tiny (`nh-grpc-jvm-grpc-stream-tiny-09071155`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.35 | 17.67 | 96.44 | 0.0 | 0.672 | 1.2 | 18 | 149 |
| 400 | 400.0 | 1.20 | 9.01 | 32.37 | 0.0 | 0.355 | 0.4 | 21 | 151 |
| 800 | 800.0 | 1.07 | 7.90 | 21.70 | 0.0 | 0.245 | 0.2 | 21 | 152 |
| 1200 | 1200.0 | 1.09 | 22.41 | 56.37 | 0.0 | 0.240 | 2.5 | 18 | 155 |
| 1600 | 1599.9 | 1.02 | 9.54 | 25.73 | 0.0 | 0.178 | 0.0 | 23 | 156 |
| 2000 | 1999.9 | 0.98 | 12.19 | 52.42 | 0.0 | 0.162 | 0.0 | 23 | 157 |
| 2400 | 2399.9 | 1.01 | 11.70 | 37.97 | 0.0 | 0.146 | 0.1 | 22 | 157 |
| 3200 | 3199.9 | 0.97 | 12.56 | 29.31 | 0.0 | 0.129 | 0.0 | 22 | 157 |
| 4000 | 3999.9 | 1.00 | 16.16 | 45.32 | 0.0 | 0.113 | 0.1 | 21 | 157 |
| 4800 | 4799.9 | 1.05 | 23.07 | 58.16 | 0.0 | 0.103 | 0.1 | 22 | 158 |

### R4b — 40 streams, tiny, 4,000→16,000 (`nh-grpc-jvm-grpc-stream-tiny-09071218`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.35 | 13.67 | 94.69 | 0.0 | 0.700 | 1.1 | 17 | 140 |
| 4000 | 3999.7 | 1.24 | 663.72 | 893.32 | 0.0 | 0.132 | 2.0 | 18 | 153 |
| 6000 | 5999.6 | 1.04 | 32.75 | 257.76 | 0.0 | 0.092 | 0.7 | 18 | 155 |
| 8000 | 7999.7 | 1.05 | 56.80 | 192.27 | 0.0 | 0.071 | 0.0 | 23 | 155 |
| 10000 | 9999.6 | 1.14 | 39.22 | 272.84 | 0.0 | 0.060 | 0.0 | 23 | 156 |
| 12000 | 11998.7 | 1.21 | 73.13 | 181.16 | 0.0 | 0.052 | 0.0 | 16 | 156 |
| 14000 | 13999.6 | 1.30 | 66.16 | 284.41 | 0.0 | 0.046 | 0.0 | 21 | 157 |
| 16000 | 15998.9 | 1.44 | 190.82 | 329.32 | 0.0 | 0.041 | 0.0 | 21 | 158 |

No knee: 16,000 msg/s delivered in full at p50 1.44 ms with nothing deferred
and the arm at ~0.66 of its core (CPU per message still falling, 0.041 ms).
The 4,000 step's p99 (664 ms) is the first-step JIT outlier seen in every
fresh-pod run at the jump from the 200/s warmup. R4c extends the ramp to
32,000.

### R4c — 40 streams, tiny, 18,000→32,000 (`nh-grpc-jvm-grpc-stream-tiny-09071319`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.37 | 17.40 | 71.62 | 0.0 | 0.674 | 0.5 | 17 | 141 |
| 18000 | 16592.4 | 2.09 | 1569.65 | 4424.47 | 1405.4 | 0.047 | 4.1 | 25 | 170 |
| 20000 | 19994.6 | 1.79 | 130.88 | 360.45 | 0.0 | 0.036 | 0.0 | 25 | 170 |
| 22000 | 21998.6 | 2.01 | 157.48 | 236.20 | 0.0 | 0.034 | 0.0 | 24 | 170 |
| 24000 | 23998.8 | 2.28 | 149.11 | 257.61 | 0.0 | 0.031 | 0.0 | 21 | 171 |
| 26000 | 25957.6 | 2.68 | 267.60 | 430.92 | 41.0 | 0.030 | 0.0 | 21 | 171 |
| 28000 | 27968.9 | 2.98 | 208.89 | 401.95 | 29.6 | 0.029 | 0.1 | 25 | 171 |
| 30000 | 29676.8 | 3.64 | 406.73 | 471.27 | 282.7 | 0.027 | 0.1 | 28 | 172 |
| 32000 | 31558.4 | 4.25 | 449.35 | 648.28 | 439.6 | 0.026 | 0.1 | 28 | 173 |

The arm is not CPU-bound anywhere in this range: CPU per message falls to
0.026 ms (~0.82 of the core at 32,000) and throttling stays under a second.
Delivery holds at 98.6% at 32,000 with p50 4.3 ms; the deferrals from 26,000
up are the client's per-stream in-flight window (256) meeting the tail —
at 800 msg/s per stream a p99 of 0.45 s means ~360 in flight, so sends wait
on the stream, not the server. Sends kept the schedule at every step (no
client-limited flag). Tiny-tier streaming capacity is therefore **> 30,000
msg/s per core**, with the latency knee (p99 crossing 400 ms) at ~30,000;
the 18,000 step is the first-step JIT outlier.


### R8 — 20 streams, realistic (`nh-grpc-jvm-grpc-stream-realistic-09071237`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.63 | 1349.19 | 2772.30 | 0.0 | 1.019 | 8.8 | 18 | 160 |
| 400 | 400.0 | 1.46 | 17.13 | 56.43 | 0.0 | 0.555 | 0.7 | 19 | 164 |
| 800 | 800.0 | 1.32 | 14.17 | 47.39 | 0.0 | 0.387 | 0.2 | 19 | 164 |
| 1200 | 1200.0 | 1.33 | 29.33 | 76.26 | 0.0 | 0.343 | 0.7 | 22 | 165 |
| 1600 | 1599.9 | 1.35 | 21.66 | 54.28 | 0.0 | 0.299 | 0.0 | 23 | 166 |
| 2000 | 1999.9 | 1.44 | 232.28 | 442.30 | 0.0 | 0.282 | 0.9 | 24 | 169 |
| 2400 | 2399.9 | 1.54 | 81.74 | 252.56 | 0.0 | 0.253 | 0.1 | 21 | 169 |
| 3200 | 3199.5 | 2.10 | 334.94 | 429.70 | 0.0 | 0.213 | 0.7 | 21 | 170 |
| 4000 | 3999.8 | 2.30 | 222.55 | 362.56 | 0.0 | 0.184 | 0.4 | 24 | 171 |
| 4800 | 4797.8 | 2.91 | 297.07 | 766.67 | 1.5 | 0.162 | 0.4 | 24 | 172 |

Delivered in full to 4,800 msg/s (1.5/s deferred at the top) with the arm at
~0.78 of its core. p50 stays under 3 ms; the p99 band from 2,000 up
(200–330 ms) is wider than tiny's at the same rates and, with throttling
under a second per step, reads as per-stream buffering of 1 KB messages
behind HTTP/2 flow control rather than CPU. R8b looks for the knee.


### R8b — 40 streams, realistic, 4,000→16,000 (`nh-grpc-jvm-grpc-stream-realistic-09071341`)

A first R8b (`…-09071300`) was voided: a peer session ran three 30 s Jobs
against the arm during its 10,000–16,000 steps. This is the redo; its
uncontaminated steps reproduce the voided run's shape (8,000 → 7,766
delivered there, 7,850 here).

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.63 | 1562.84 | 3231.84 | 0.0 | 1.012 | 3.4 | 17 | 155 |
| 4000 | 3893.5 | 3.11 | 5532.81 | 8384.94 | 106.2 | 0.203 | 4.3 | 21 | 173 |
| 6000 | 5997.3 | 6.51 | 275.01 | 817.66 | 0.0 | 0.138 | 0.3 | 22 | 176 |
| 8000 | 7849.5 | 93.72 | 1518.67 | 5462.03 | 148.9 | 0.112 | 0.5 | 18 | 181 |
| 10000 | 8173.3 | 118.31 | 4741.40 | 18112.05 | 1797.4 | 0.108 | 0.1 | 20 | 177 |
| 12000 | 8139.8 | 273.27 | 4612.69 | 26017.27 | 3766.7 | 0.108 | 0.1 | 20 | 188 |
| 14000 | 8191.3 | 360.27 | 3172.20 | 24009.24 | 5716.4 | 0.107 | 0.4 | 19 | 189 |
| 16000 | 8159.2 | 162.23 | 3487.30 | 21592.28 | 7751.7 | 0.107 | 0.3 | 24 | 192 |

The realistic streaming knee: 6,000 delivers in full at p50 6.5 ms; 8,000
delivers 7,850 with p50 94 ms and the first deferrals; from 10,000 up the
arm holds a flat **~8,150–8,200 msg/s plateau** whatever is offered. It is not
the cgroup quota — CPU per message sits at 0.107 ms, ~0.87 of the core, and
throttling stays under a second — it is the single event-loop thread the
`:direct` executor runs everything on, decoding and re-encoding a 1 KB
message per echo. `stream_write_blocked` (Envoy's connection write buffer at
its high watermark) climbs from 16,040 events at 12,000 to saturation at
16,000: the backpressure is at the connection, which is where a streaming
server should push it.

The collector flags the 12,000–16,000 steps unhealthy (streams closed without
a `grpc-status`: 0, 27, 32 of 40). The Job log explains it — "13 gRPC
stream(s) still open after the 500 ms drain window": with thousands of
messages queued per stream the client's half-close-and-drain gives up before
the echoes arrive, and the stream ends without a status. A harness artifact
of overload (a longer `--stream-drain-duration` would clear it), not a server
fault: sent and received differ by the in-flight tail only (903,760 vs
899,481 at 12,000) and `stream_resets` is 0 throughout. The 4,000 step is the
first-step JIT outlier.

### Phase C — what the interaction-model switch buys (`grpc-jvm` unary → stream)

Same arm, same core, same payload echoed per message, same client budget
(4,096 in flight; streams add a 256 in-flight cap per stream). The switch
replaces one HTTP/2 stream per request with N persistent bidi streams and a
message per request. Unary numbers from Phase B; streaming from the runs
above; "at matched rate" pairs steps at the same offered rate.

| | unary | stream (20 / 40 streams) | switch buys |
|---|---|---|---|
| **tiny** knee / plateau (per s) | ~10,500 / ~10,700 | latency knee ~30,000 / > 31,500, arm at 0.82 core | ~3× |
| tiny CPU per message at 4,800 / 8,000 | 0.138 / 0.095 ms | 0.103 / 0.071 ms | 25% cheaper |
| tiny p50 / p99 at 4,800 (ms) | 2.21 / 80.7 | 1.05 / 23.1 | |
| tiny p50 / p99 at 16,000 (ms) | — (past knee) | 1.44 / 191 | |
| **realistic** knee / plateau (per s) | 4,000 / ~4,600 | ~6,500 / ~8,200 | 1.8× |
| realistic CPU per message at 4,000 | 0.216 ms | 0.184 ms | 15% cheaper |
| realistic p50 / p99 at 4,000 (ms) | 10.4 / 782 | 2.30 / 223 | |
| realistic p50 / p99 at 2,400 (ms) | 4.14 / 2,635 (JIT outlier) → 3.28 / 219 at 2,800 | 1.54 / 81.7 | |
| RSS at plateau (MB) | ~195 | ~175–190 | |

- **Streaming is worth 1.8× more capacity on 1 KB messages and ~3× on tiny
  ones**, on top of unary gRPC, at 15–25% less CPU per message and with
  p50 at or under 3 ms all the way to the knee. The gain is the per-request
  overhead unary cannot amortize — stream setup, headers, trailers, the
  per-RPC bookkeeping in grpc-java — which is a fixed cost that matters more
  the smaller the message: 26 µs per tiny message at 32,000/s versus 107 µs
  per 1 KB message at the realistic plateau.
- **The realistic ceiling is the event loop, not the quota.** At ~8,200 msg/s
  the `:direct` arm runs its one event-loop thread at ~0.87 core with the
  cgroup never throttling. That is the cost of `:direct` (no executor
  hand-off, so no parallelism either) on a 1-CPU pod; on a pod with N cores
  and N event loops it is N× this number, which unary — bound by per-request
  work spread across the same loops — would also scale.
- **August's ratios, corrected on one instrument.** August put streaming at
  7.5× unary gRPC and 16× REST; measured with the same Nighthawk on both
  sides it is 1.8× unary and **11× REST** on the realistic body (~3× and
  > 32× on tiny). The difference is entirely August's under-measurement of
  unary gRPC by the k6 driver. Streaming's absolute numbers (August 15–16k
  tiny at 40 streams) were driver-bound too: it is > 30,000.
- **Overload behaviour is the best of the ladder**: a flat plateau at any
  offered rate, zero errors, zero resets, backpressure at the connection.
  The only casualty is the harness's own drain window.

Disclosures: all streams on one HTTP/2 connection (Nighthawk's
`--connections` is a cap); 256 in flight per stream, which shapes the
deferrals from 26,000 up on tiny (tail × per-stream rate); the 0.5 s drain
leaves streams unclosed past the realistic knee, flagged in the tables; the
Clojure `stream_driver` cross-check (`streamCheck`) was not run — the fork's
own P2 acceptance against this arm (30 s steps, 2 workers) is the
independent cross-check and agrees at every shared rate (16,000 at p50
1.85 ms there, 1.44 ms here).

## Re-baseline, 2026-09-07/08 — VT default, agent-free images, compiled codec

Phases A–C measured the `:direct` executor on chart 0.2.6, whose JVM images
loaded the Pyroscope agent unconditionally and whose codec went through
protobuf-java's `DynamicMessage`. Three things changed after that, and all
three move the gRPC rows:

- **grpc-java's position on `:direct`.** It will not be optimised further for
  lack of use, so the ladder's baseline executor is now the library default
  (virtual threads); `:direct` becomes the tuned variant.
- **The agent was loaded even when disabled** (`-javaagent` in the image
  entrypoint). A loaded JVMTI agent turns on the JVM's virtual-thread
  transition hooks (`JvmtiThreadState` per mount, `VTMS_transition`), which
  cost only the VT arm. Chart 0.2.8 injects the agent through
  `JAVA_TOOL_OPTIONS` when `profiling.enabled`, and not otherwise.
- **clj-protobuf 0.2.0/0.2.1's descriptor-compiled codec** replaced
  `DynamicMessage` with per-descriptor reader/writer tables over
  `CodedInput/OutputStream`.

Everything below is the same arm, node, client settings, warmup and step
length as Phases A–C. Chart 0.2.8 = clj-protobuf 0.2.1 (protobuf-java
4.35.1); chart 0.2.9 = 0.2.2 (4.36.1); both agent-free.

### VT (library default), unary (`nh-grpc-jvm-grpc-unary-realistic-09080033`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 2.12 | 4626.58 | 4956.09 | 0.0 | 1.689 | 15.7 | 20 | 132 |
| 1000 | 1000.0 | 2.20 | 24.23 | 71.60 | 0.0 | 0.660 | 0.8 | 20 | 132 |
| 2000 | 1999.9 | 3.60 | 47.55 | 111.67 | 0.0 | 0.434 | 1.3 | 19 | 133 |
| 2400 | 2399.7 | 4.25 | 69.19 | 114.94 | 0.0 | 0.372 | 1.0 | 19 | 135 |
| 2800 | 2799.6 | 5.16 | 113.30 | 270.39 | 0.1 | 0.327 | 1.7 | 23 | 137 |
| 3200 | 3178.7 | 7.72 | 1841.23 | 4214.88 | 21.1 | 0.295 | 7.1 | 49 | 178 |
| 3600 | 3598.4 | 7.41 | 107.07 | 222.35 | 0.1 | 0.262 | 3.0 | 50 | 177 |
| 4000 | 3999.7 | 10.24 | 322.81 | 684.03 | 0.2 | 0.236 | 5.1 | 36 | 178 |
| 4400 | 4210.0 | 21.85 | 2155.87 | 2943.61 | 188.9 | 0.228 | 12.9 | 38 | 178 |
| 4800 | 4678.9 | 39.83 | 1647.05 | 1966.60 | 83.9 | 0.208 | 11.9 | 39 | 176 |

### VT (library default), 40 streams (`nh-grpc-jvm-grpc-stream-realistic-09080057`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.74 | 941.88 | 1076.95 | 0.0 | 1.138 | 15.9 | 17 | 130 |
| 2000 | 1994.7 | 2.16 | 1070.07 | 1191.05 | 0.0 | 0.354 | 13.1 | 17 | 182 |
| 4000 | 3999.0 | 3.33 | 1080.89 | 1263.67 | 0.0 | 0.210 | 7.1 | 16 | 149 |
| 6000 | 5999.8 | 6.82 | 528.11 | 620.30 | 0.0 | 0.152 | 5.3 | 18 | 150 |
| 8000 | 7613.1 | 1078.39 | 2186.15 | 2497.05 | 311.1 | 0.124 | 30.5 | 22 | 176 |
| 10000 | 7866.0 | 1216.35 | 2209.87 | 2380.53 | 2040.9 | 0.123 | 32.7 | 23 | 179 |

### `:direct`, unary (`nh-grpc-jvm-grpc-unary-realistic-09080112`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.77 | 2222.19 | 4778.89 | 0.0 | 1.143 | 11.9 | 19 | 134 |
| 1000 | 999.9 | 1.48 | 16.01 | 52.15 | 0.0 | 0.455 | 0.3 | 19 | 135 |
| 2000 | 1999.9 | 1.89 | 50.53 | 136.36 | 0.0 | 0.341 | 0.2 | 18 | 136 |
| 2400 | 2399.7 | 2.72 | 1274.61 | 2256.27 | 0.3 | 0.320 | 9.0 | 28 | 153 |
| 2800 | 2799.9 | 2.52 | 139.11 | 250.74 | 0.1 | 0.267 | 0.3 | 28 | 153 |
| 3200 | 3199.5 | 3.08 | 345.31 | 810.35 | 0.2 | 0.241 | 1.2 | 28 | 153 |
| 3600 | 3599.8 | 3.45 | 182.28 | 305.00 | 0.2 | 0.219 | 0.2 | 29 | 153 |
| 4000 | 3959.4 | 4.67 | 1308.23 | 3401.06 | 40.6 | 0.203 | 0.8 | 30 | 158 |
| 4400 | 4399.7 | 4.81 | 188.38 | 347.82 | 0.2 | 0.187 | 0.6 | 31 | 158 |
| 4800 | 4742.0 | 6.53 | 1148.32 | 3069.84 | 57.5 | 0.177 | 1.1 | 31 | 162 |

### `:direct`, 40 streams (`nh-grpc-jvm-grpc-stream-realistic-09080136`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.53 | 150.01 | 1071.05 | 0.0 | 0.828 | 3.4 | 17 | 127 |
| 2000 | 1999.8 | 1.37 | 1668.22 | 3581.41 | 0.0 | 0.278 | 3.3 | 18 | 131 |
| 4000 | 3999.8 | 1.70 | 157.69 | 245.71 | 0.0 | 0.174 | 1.4 | 18 | 137 |
| 6000 | 5999.4 | 2.62 | 164.15 | 209.89 | 0.0 | 0.127 | 1.0 | 15 | 138 |
| 8000 | 7989.6 | 5.62 | 387.30 | 923.60 | 1.2 | 0.100 | 0.8 | 18 | 141 |
| 10000 | 9976.0 | 32.22 | 285.74 | 1777.27 | 21.7 | 0.085 | 0.0 | 18 | 142 |

### What the re-baseline says

| | VT (default) | `:direct` | direct's advantage |
|---|---|---|---|
| unary CPU/req at 2,000 | 0.434 ms | 0.341 ms | 27% |
| unary CPU/req at 4,800 | 0.208 ms | 0.177 ms | 18% |
| unary p50 at 4,800 | 39.8 ms | 6.5 ms | 6× |
| stream knee / plateau | 6,000 / ~7,900 msg/s | ≥ 10,000 (still delivering) | ~25% capacity |
| stream CPU/msg at 6,000 | 0.152 ms | 0.127 ms | 20% |
| RSS at plateau | ~180 MB | ~142 MB | |

- **The executor gap is real but smaller than the confounded runs showed.**
  With the agent out of the image it is 15–27% CPU per request on unary and
  ~25% capacity on streams, not the 40–70% measured on chart 0.2.6. What
  survives unambiguously is the tail: VT's p50 is roughly double at every
  matched rate and its throttling an order of magnitude higher near the knee.
  For a 1-CPU pod with provably non-blocking handlers, `:direct` remains the
  better setting; for anything that may block, VT is the only safe one and
  now costs less than the ladder implied.
- **The ladder's gRPC rows improve.** `:direct` streaming reaches 9,976 msg/s
  per core at 0.085 ms/msg where Phase C measured ~8,200 at 0.107, and unary
  holds 4,742 at 0.177 ms where Phase B's plateau was ~4,600 at ~0.20. The
  protocol and interaction-model conclusions are unchanged in direction and
  slightly larger in magnitude.

### Compiled codec, measured on the same image (chart 0.2.7, `-Dclj-protobuf.codec=dynamic` as the control)

| arm / mode | step | DynamicMessage | compiled | saving |
|---|---|---|---|---|
| `:direct`, stream | 3,500 msg/s | 0.211 ms/msg | 0.184 | 13% |
| `:direct`, stream | 5,000 msg/s | 0.161 | 0.144 | 11% |
| `:direct`, unary | 2,000 rps | 0.359 ms/req | 0.338 | 6% |
| `:direct`, unary | 3,000 rps | 0.300 | 0.273 | 9% |
| VT, stream | 2,000 msg/s | 0.414 | 0.349 | 16% |
| VT, stream | 5,000 msg/s | 0.219 | 0.181 | 17% |
| VT, unary | 2,000 rps | 0.466 ms/req | 0.444 | 5% |
| VT, unary | 3,000 rps | 0.358 | 0.316 | 12% |

The frame diff is the stronger evidence: on the `:direct` streaming path
`com.google.protobuf` falls from 26% of samples to 2%, `FieldSet`,
`SmallSortedMap` and `Descriptors$…getFeatures` disappear entirely, and the
top cost becomes syscalls at 35%. The codec is no longer the bottleneck
there — the socket is. On VT the compiled codec also moves the streaming
knee past 5,000 msg/s where `DynamicMessage` collapsed.

Two smaller results from the same night:

- **protobuf-java 4.36.1 vs 4.35.1** (chart 0.2.9 vs 0.2.8, four runs): no
  measurable difference. Identical CPU per request on `:direct` at every
  matched step, 0–5% in 4.36.1's favour on VT. Take the bump for its own
  sake, not for throughput.
- **Netty's leak detector** (`io.netty.leakDetection.level=disabled` vs the
  default): 4% of CPU per request at 1,000 rps, 2% at 2,000, ~1% at the knee,
  nothing measurable on streams. Worth setting explicitly; not a headline.
- **Pinning the virtual-thread scheduler to one carrier**
  (`jdk.virtualThreadScheduler.parallelism=1`): no effect (0.679/0.448/0.315
  vs 0.700/0.444/0.316 ms/req; streams likewise). The VT cost is per-mount,
  not carrier contention. Note: Helm's `--set-string` kept only the first
  flag of the pair, so `maxPoolSize` was left at its default; the scheduler
  still ran a single carrier.

### Native image, for completeness (chart 0.2.6, VT default, `DynamicMessage`)

Four runs, `soak/results/2026-09-07-native/`: realistic unary knee ~1,000 rps
and plateau ~1,650–1,700 at ~0.6 ms/req; realistic streaming plateau
~2,750 msg/s at 0.36 ms/msg; tiny streaming ~10,000–10,300 at 0.097; tiny
unary knee ~2,800. RSS 39–60 MB below the knee against the JVM's ~140, no
JIT warmup at any step, and heap growth to ~200 MB at the top tiny-unary
steps. Roughly half the JVM-VT arm and a third of `:direct` on this hardware;
it was not re-run on the agent-free charts because it has no JVM and no agent.

## Direct linking at runtime, 2026-09-08

`clojure.lang.Var.getRawRoot` was 3.2% of samples under the compiled codec — every
cross-namespace `defn` call pays one. rules_clj 0.2.4 adds a build-time
`direct_linking` attribute for that, but it cannot reach the code that matters here:
clj-protobuf is published to Clojars as source (its 0.2.2 jar holds ten `.clj`
entries and no classes), so its namespaces are compiled by Clojure at load time and
were never compiled ahead of time. A caller that *is* compiled ahead of time may not
link into them at all — the class a direct call names exists only once the callee is
loaded — which rules_clj refuses at build time.

What does reach it is the same option applied to the runtime compiler:
`-Dclojure.compiler.direct-linking=true` on the arm's JVM. Everything Clojure compiles
at load time then emits direct calls, and the references resolve because caller and
callee share one classloader. Chart 0.2.9, stock image, one environment variable,
against an identical baseline run in the same hour.

### unary, baseline (`nh-grpc-jvm-grpc-unary-realistic-09081120`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.83 | 3189.37 | 6125.52 | 0.0 | 1.253 | 12.0 | 26 | 180 |
| 1000 | 1000.0 | 1.52 | 22.10 | 79.13 | 0.0 | 0.461 | 0.9 | 26 | 181 |
| 2000 | 1999.9 | 1.97 | 46.54 | 122.10 | 0.0 | 0.335 | 0.9 | 21 | 185 |
| 3000 | 2916.0 | 4.03 | 2297.95 | 9450.29 | 81.3 | 0.283 | 26.6 | 29 | 207 |

### unary, linked (`nh-grpc-jvm-grpc-unary-realistic-09081055`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.77 | 2735.47 | 4257.74 | 0.0 | 1.193 | 9.2 | 19 | 178 |
| 1000 | 1000.0 | 1.54 | 21.98 | 65.80 | 0.0 | 0.437 | 0.4 | 19 | 179 |
| 2000 | 1999.9 | 1.80 | 44.85 | 105.96 | 0.0 | 0.324 | 0.8 | 27 | 182 |
| 3000 | 2999.9 | 2.43 | 104.82 | 230.93 | 0.1 | 0.245 | 1.8 | 29 | 188 |

### 40 streams, baseline (`nh-grpc-jvm-grpc-stream-realistic-09081132`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.54 | 145.00 | 1005.06 | 0.0 | 0.931 | 2.3 | 20 | 206 |
| 2000 | 1999.8 | 1.56 | 723.39 | 2270.43 | 0.0 | 0.315 | 9.7 | 20 | 218 |
| 3500 | 3499.8 | 1.53 | 87.03 | 261.19 | 0.0 | 0.191 | 1.1 | 29 | 224 |
| 5000 | 4998.0 | 2.09 | 125.39 | 203.19 | 0.0 | 0.146 | 0.6 | 29 | 226 |

### 40 streams, linked (`nh-grpc-jvm-grpc-stream-realistic-09081108`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.52 | 30.47 | 698.71 | 0.0 | 0.788 | 1.7 | 25 | 167 |
| 2000 | 1999.9 | 1.30 | 765.46 | 2349.73 | 0.0 | 0.260 | 4.2 | 28 | 192 |
| 3500 | 3498.8 | 1.47 | 689.47 | 2592.60 | 1.0 | 0.186 | 1.4 | 28 | 197 |
| 5000 | 4999.8 | 1.73 | 91.49 | 221.76 | 0.0 | 0.136 | 0.1 | 28 | 199 |

### What the property buys

| workload | step | baseline | linked | saving |
|---|---|---|---|---|
| unary | 1,000 rps | 0.461 ms/req | 0.437 | 5% |
| unary | 2,000 rps | 0.335 | 0.324 | 3% |
| unary | 3,000 rps | 0.283 | 0.245 | 13% |
| stream | 2,000 msg/s | 0.315 ms/msg | 0.260 | 17% |
| stream | 3,500 msg/s | 0.191 | 0.186 | 3% |
| stream | 5,000 msg/s | 0.146 | 0.136 | 7% |

`Var.getRawRoot` falls from 3.2% of samples to 1.12%, the remainder being
`clojure.core`'s own calls, which `clojure.jar` already ships linked. p50 improves at
every matched step (unary at 3,000: 4.03 → 2.43 ms), and the linked arm delivers all
3,000 rps where the baseline sheds to 2,916. clj-protobuf's own suite — 56 tests, 637
assertions — passes under the property, and the library defines no dynamic vars, no
`^:redef` fns and never uses `alter-var-root` or `with-redefs`, so nothing in the
measured path depends on late binding.

**The two levers cover disjoint code, and neither covers both.** The runtime property
links what Clojure compiles at load time: clj-protobuf's codec, Pedestal, jsonista.
rules_clj's `direct_linking` attribute links what the build compiles ahead of time:
clj-grpc's own namespaces. Today the attribute cannot be used here at all — a single
call in `clj-grpc.service` into clj-protobuf's runtime disqualifies the whole target,
verified against rules_clj main — so the property is the only lever that reaches the
hot path. The mechanism that would cover both is compiling a Maven source jar inside
the consumer's build, sketched on rules_clj#18 and unbuilt.

Caveat for anyone extending this: the property is process-wide and changes late
binding for every namespace loaded from source, so a service that redefines at runtime
must not take it without checking.

### A caveat on image digests — corrected 2026-09-08

An earlier version of this section said the native image is not reproducible while
"the JVM and REST images are reproducible". The second half is wrong, and the way it
is wrong matters more than the fact.

**No image here is byte-reproducible.** The same source built twice into separate
output bases produces deploy jars differing in 42 entries by CRC, all anonymous
function classes in `clj-grpc.soak.stream-driver`, `clj-grpc.coldstart.steady` and
`clj-grpc.coldstart.measure` — most likely a persistent compile worker's JVM-global
counters, unproven. The native image drifts most visibly, but it is not special.

**And a published chart version is not immutable.** Every merge to main runs the image
build, re-pushes any image whose fresh digest differs from the registry's, and
republishes the chart at whatever version `Chart.yaml` names — overwriting it in place.
Combined with the above, a docs-only merge can redefine a chart that has already been
published and measured against. Chart 0.2.11 pinned `soak-grpc-native@sha256:eed3976a`
when it was published and pins `20afcb26` now, changed by two later documentation
merges.

So "pin the chart version and you pin the images" does not hold, and every result in
this document was recorded against a chart version rather than a digest.

**What that costs these results: nothing, checked rather than assumed.** For charts
0.2.8, 0.2.9, 0.2.10 and 0.2.11, the JVM and REST digests each chart pins today are
identical to the ones reported when it was published; only the native arm's digest
moved (in 0.2.9 and 0.2.11). Every comparison in this document runs on JVM arms, so
each pair ran on the images its chart still names:

| chart | soak-grpc-jvm | soak-grpc-jvm-interop | soak-rest | soak-grpc-native |
|---|---|---|---|---|
| 0.2.8 | `b7d8ba22` | — | `13895fa8` | `a815b531` |
| 0.2.9 | `35b01808` | — | `13895fa8` | `50e8c119` → `dc475350` |
| 0.2.10 | `313ed480` | — | `13895fa8` | `526cec7e` |
| 0.2.11 | `313ed480` | `79edecfa` | `13895fa8` | `eed3976a` → `20afcb26` |

The native set elsewhere in this document ran on chart 0.2.6 as a single self-consistent
group and was never diffed across charts, which was already its stated caveat.

**For future campaigns**, record the image digest with each run rather than the chart
version. The durable fix is a policy choice: either make the build refuse to publish a
chart version that already exists, or make the builds reproducible so a re-push is a
no-op. Neither is done.

## The ladder — what is on the table for an existing REST service

Per core, 1-CPU pods, one instrument, each rung differing from the one
below in exactly one thing. "Capacity" is the plateau of delivered
requests or messages per second; "cost" is the arm's CPU per request at
600 offered (realistic) / 800 (tiny), where every arm is below its knee.

| rung | switch | realistic (1.3 KB JSON / 1 KB pb) capacity | cost at 600 | p99 at 600 | tiny capacity | migration cost |
|---|---|---|---|---|---|---|
| 0 | REST HTTP/1.1 (today) | ~750 rps | 1.59 ms | 134 ms | ~925 | — |
| 1 | → h2c | ~750 (collapses under overload) | 1.65 ms | 108 ms | ~925 | a config flag on the server; clients must speak h2c |
| 2 | → gRPC unary | ~4,700 (6×) | 0.56 ms | 13.7 ms | ~10,700 (11×) | new clients, protobuf schema, serialization; API shape unchanged |
| 3 | → gRPC stream | ~10,000 (13×) | ~0.4 ms\* | ~15 ms\* | > 31,500 (> 34×) | API contract changes: persistent connections, message ordering, backpressure |

\* streaming at 600 msg/s is below any measured step (400: 0.555 ms, 800:
0.387 ms, p99 17 / 14 ms); interpolated. The gRPC capacities are the
re-baselined `:direct` numbers from the section above (chart 0.2.8, compiled
codec, agent-free); Phases B and C measured ~4,600 and ~8,200 on chart 0.2.6.
With the library's default VT executor the same rows read ~4,700 unary
(at 18–27% more CPU per request) and ~7,900 streaming.

The money is on rung 2. Rung 1 buys nothing and costs a little; rung 3 buys
1.8× more on top of rung 2 (3× on tiny) at the price of a different API
contract. For a service at REST's knee today, moving to gRPC unary frees
~85% of its cores at the same load; streaming frees ~90%. What is not on the
table anywhere in the ladder: the JIT warmup of a fresh 1-CPU JVM pod
(minutes, every arm) and the first-step outliers it leaves, which are a
deployment concern (warm before serving) rather than a protocol one.

Where the 1.59 → 0.56 ms goes is the next section.

## Profiled repeats — where the per-request cost goes

Pyroscope's Java agent (async-profiler 2.9.1, `itimer` at 100 Hz, in-process
so kernel frames appear as their libc entry points) on the JVM arms, chart
0.2.6 with `profiling.enabled=true`. Two questions: does the agent change
the numbers, and what is each arm doing per request.

### Agent overhead — R3 repeated with the agent on (`nh-grpc-jvm-grpc-unary-tiny-09071406`)

| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.66 | 1625.03 | 2046.23 | 0.0 | 1.172 | 4.2 | 21 | 159 |
| 400 | 400.0 | 1.45 | 11.67 | 33.99 | 0.0 | 0.537 | 0.1 | 24 | 159 |
| 800 | 800.0 | 1.44 | 10.83 | 28.26 | 0.0 | 0.383 | 0.0 | 24 | 160 |
| 1200 | 1200.0 | 1.39 | 16.78 | 157.72 | 0.0 | 0.331 | 0.0 | 20 | 160 |
| 1600 | 1599.9 | 1.40 | 15.46 | 71.22 | 0.0 | 0.298 | 0.0 | 22 | 160 |
| 2000 | 2000.0 | 1.50 | 32.18 | 80.25 | 0.0 | 0.270 | 0.6 | 23 | 162 |
| 2400 | 2399.9 | 1.57 | 43.12 | 235.78 | 0.1 | 0.241 | 0.3 | 19 | 162 |
| 2800 | 2799.9 | 1.77 | 71.98 | 180.98 | 0.1 | 0.214 | 0.0 | 25 | 164 |
| 3200 | 3199.9 | 1.78 | 86.88 | 200.47 | 0.1 | 0.195 | 0.0 | 25 | 166 |
| 3600 | 3599.8 | 1.86 | 69.97 | 180.81 | 0.1 | 0.181 | 0.0 | 21 | 170 |
| 4000 | 3995.2 | 1.99 | 83.80 | 273.97 | 4.7 | 0.166 | 0.1 | 22 | 172 |
| 4400 | 4399.7 | 2.07 | 49.24 | 124.40 | 0.1 | 0.153 | 0.0 | 21 | 172 |
| 4800 | 4799.7 | 2.23 | 72.66 | 257.36 | 0.2 | 0.144 | 0.0 | 23 | 173 |

Against the unprofiled R3 (Phase B): delivered identical at every step,
p50 within 0.1 ms, CPU per request +1–6% (0.507 → 0.537 ms at 400,
0.138 → 0.144 at 4,800; median +3%), p99 inside run-to-run noise (better at
five steps, worse at seven). The REST arm's ramped repeat (below) costs 1.3%
more CPU per request at 600 than its unprofiled run. The agent is cheap
enough that shares can be read; absolute costs below are the profiled
run's own `cpu ms/req`, so they carry the agent's few percent.

### Attribution at matched moderate load

One ramped run per arm, the last step read: REST h1 at 600 rps
(`nh-rest-h1-http1-realistic-09071619`), gRPC unary at 3,000
(`nh-grpc-jvm-grpc-unary-realistic-09071632`), gRPC stream at 5,000 msg/s over
40 streams (`nh-grpc-jvm-grpc-stream-realistic-09071644`), all on the 1 KB
body and all at ~0.75–0.8 of the arm's knee. (A first attempt with a
single step straight after the 200 rps warmup was discarded: it profiled
the first-step JIT outlier — 23% of unary's samples in the C2 compiler and
GC, delivered down 15% — the same artifact every fresh-pod run shows.
Its logs are kept under `single-step/`.) Cells are *share of samples ·
ms per request* — the share times the step's measured CPU per request.

| layer (self time) | REST h1 @600 (1.61 ms/req) | gRPC unary @3,000 (0.27 ms/req) | gRPC stream @5,000 (0.16 ms/msg) |
|---|---|---|---|
| syscalls: writev / read / epoll / futex | 28% · 0.450 | 20% · 0.054 | 24% · 0.039 |
| Clojure runtime (maps, Vars, keywords, seqs) | 25% · 0.408 | 5% · 0.014 | 5% · 0.009 |
| Java std (collections, strings, atomics, locks) | 15% · 0.239 | 10% · 0.028 | 10% · 0.016 |
| Jetty | 10% · 0.168 | — | — |
| Pedestal / Ring | 4% · 0.056 | — | — |
| JSON (jsonista / Jackson) | 4% · 0.063 | — | — |
| Netty | — | 21% · 0.057 | 13% · 0.021 |
| grpc-java | — | 8% · 0.023 | 4% · 0.006 |
| protobuf-java (descriptor-driven access) | — | 20% · 0.054 | 26% · 0.041 |
| JIT + GC (libjvm) | 7% · 0.110 | 5% · 0.015 | 6% · 0.010 |
| JVM dispatch stubs | 5% · 0.076 | 2% · 0.006 | 1% · 0.002 |
| other (copy/intrinsic stubs, unresolved) | 3% · 0.042 | 7% · 0.019 | 10% · 0.016 |
| application code | — | — | — |


What the three columns say:

- **REST's extra ~1.3 ms per request is not JSON.** Parsing and printing the
  1.3 KB body cost 0.06 ms (4%). The cost is the request pipeline around it:
  the Clojure runtime at 0.41 ms — persistent-map `assoc`/`valAt`, `Var`
  and keyword lookups, lazy seqs, i.e. Pedestal's interceptor chain building
  and reading the request and response maps — plus 0.24 ms of Java
  collections and locks under it, 0.17 ms of Jetty, and 0.45 ms of syscalls.
  Application code is 0.1%.
- **Syscalls are 8× more expensive per request on REST** (0.45 vs 0.054
  ms): HTTP/1.1 writes each response with its own `writev` on its own
  connection (`writev` alone is 13% of REST), and Jetty's thread-pool
  hand-off shows as `pthread_cond_signal`/futex, where Netty's event loop
  batches frames onto one multiplexed socket with no hand-off (`:direct`).
- **On the gRPC arms the biggest software cost is protobuf, and it is the
  generic path.** 20–26% of samples sit in `Descriptors$FieldDescriptor.getType`,
  `getFeatures`, `SmallSortedMap`, `FieldSet` and `CodedInputStream.readPrimitiveField`
  beneath `clj_protobuf.codec/proto-value` and `get-field`: descriptor-driven
  field access, not generated-class parsing. That is exactly what the typed
  `interop=true` emitter path (protoc-gen-clojure 0.5.1) removes — the
  clj-protobuf suite measured its encode at 412 ns vs 650 ns for this path
  on a deep shape — so ~0.04–0.05 ms per request is on the table on both
  gRPC arms without touching the transport.
- **Streaming's gain over unary is visible as grpc-java shrinking** from
  8.4% (0.023 ms) to 3.8% (0.006 ms): per-RPC setup, headers, trailers and
  `GrpcHttp2InboundHeaders` handling amortized over a stream. Netty's share
  drops too (0.057 → 0.021 ms) as frames batch. What is left at 0.16 ms is
  protobuf + syscalls + copies — the message itself.
- **JIT + GC is 5–7% everywhere** at steady state; the same arms show
  20–50% in the compiler during the first step after a rate jump, which is
  the outlier the tables exclude and the profiles above avoid.

Logs (gzipped) and `tables.md` in `soak/results/2026-09-07-profiled/`; the
attribution reads are reproducible with `soak/pyro.py <service> <from> <until>`
against the arm's `<arm>-java` service in Pyroscope for the step's
`#NH-STEP` window.
