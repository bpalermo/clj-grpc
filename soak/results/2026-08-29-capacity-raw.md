# Raw per-step capacity tables — 2026-08-29/30 campaign

Backing data for the summary in `soak/README.md` and for
[grpc/grpc-java#13012](https://github.com/grpc/grpc-java/issues/13012).
Setup: talos-main (arm64), identical 1-CPU/1-Gi Guaranteed pods, one arm per
4-core worker, sequential runs, k6 `ramping-arrival-rate` 200→2400 req/s in
2-minute steps (10 s climb + 110 s hold), tiny unary echo, warm JVM client on
its own worker. Each row: the step's 110 s hold window. grpc-java 1.83.1,
non-shaded Netty 4.2.16.Final, Clojure 1.12.5 via clj-grpc 0.1.4.

Known artifacts, disclosed: the first step of every JVM-based run is JIT
warmup (the arm rolls immediately before its run). Run 1's arm was
liveness-killed at ~00:06:41Z and again near ramp top (probes later relaxed
to failureThreshold 60). Run 3's arm was liveness-killed at ~00:56Z. Run 5's
1400-offered window shows a transient inversion (dropped-iterations spike
that window) inconsistent with its neighbors.

## Run 1 — grpc-native, VT executor (start 23:49:12Z)

| offered | delivered | p50ms | p99ms | dropped/s |
|---|---|---|---|---|
| 200 | 200.3 | 2.6 | 9.1 | 0 |
| 400 | 400.5 | 2.7 | 13.1 | 0 |
| 600 | 600.0 | 2.9 | 23.7 | 0 |
| 800 | 800.8 | 3.8 | 48.9 | 0 |
| 1000 | 999.5 | 11.0 | 81.3 | 0.3 |
| 1200 | 1197.6 | 30.9 | 187.8 | 2.8 |
| 1400 | 1379.3 | 82.3 | 457.7 | 20.8 |
| 1600 | 1499.7 | 426.0 | 2226.4 | 91.9 |
| 1800 | 1580.6 | 760.3 | 2455.1 | 187.6 |
| 2000 | 1567.6 | 832.9 | 2465.4 | 432.4 |
| 2200 | 1607.7 | 823.3 | 2459.5 | 598.8 |
| 2400 | 1524.8 | 840.1 | 2496.5 | 818.0 |

## Run 2 — grpc-jvm, VT executor (start 00:13:46Z)

| offered | delivered | p50ms | p99ms | dropped/s |
|---|---|---|---|---|
| 200 | 207.6 | 3.9 | 4326.1 | 0.0 |
| 400 | 400.2 | 2.8 | 12.8 | 0.0 |
| 600 | 600.2 | 3.0 | 24.9 | 0.0 |
| 800 | 800.4 | 3.3 | 44.5 | 0.0 |
| 1000 | 1000.6 | 3.9 | 59.5 | 0.0 |
| 1200 | 1201.8 | 4.9 | 86.8 | 0.0 |
| 1400 | 1393.0 | 10.7 | 827.9 | 6.9 |
| 1600 | 1600.7 | 16.0 | 238.7 | 0.0 |
| 1800 | 1791.7 | 34.9 | 324.9 | 0.0 |
| 2000 | 1991.9 | 69.2 | 661.8 | 10.8 |
| 2200 | 2059.3 | 126.6 | 972.5 | 132.4 |
| 2400 | 1984.3 | 254.6 | 2069.8 | 339.7 |

## Run 3 — rest (Pedestal/Jetty 12, HTTP/1.1, platform pool) (start 00:38:20Z)

| offered | delivered | p50ms | p99ms | dropped/s |
|---|---|---|---|---|
| 200 | 201.9 | 207.1 | 10000.0 | 3.8 |
| 400 | 396.8 | 2692.3 | 7685.4 | 9.5 |
| 600 | 601.4 | 4.6 | 239.9 | 0.0 |
| 800 | 799.9 | 9.1 | 95.5 | 0.0 |
| 1000 | 962.5 | 1634.6 | 2486.2 | 30.0 |
| 1200 | 960.7 | 1697.2 | 2487.9 | 239.1 |
| 1400 | 959.1 | 1700.3 | 2488.8 | 441.0 |
| 1600 | 958.3 | 1696.7 | 2488.3 | 642.1 |
| 1800 | 957.1 | 1689.7 | 2490.3 | 843.1 |
| 2000 | 43.2 | 0.0 | 9696.5 | 1942.6 |
| 2200 | 244.6 | 6572.0 | 10000.0 | 1954.6 |
| 2400 | 348.1 | 5500.6 | 7460.2 | 1995.7 |

## Run 4 — grpc-jvm, directExecutor (start 01:04:21Z)

| offered | delivered | p50ms | p99ms | dropped/s |
|---|---|---|---|---|
| 200 | 206.6 | 3.1 | 4448.4 | 0.0 |
| 400 | 400.4 | 2.6 | 9.6 | 0.0 |
| 600 | 599.5 | 2.8 | 21.1 | 0.0 |
| 800 | 800.3 | 3.0 | 38.3 | 0.0 |
| 1000 | 1000.2 | 3.4 | 50.4 | 0.0 |
| 1200 | 1200.7 | 4.0 | 86.6 | 0.0 |
| 1400 | 1403.1 | 4.9 | 113.0 | 0.0 |
| 1600 | 1602.3 | 9.4 | 222.5 | 0.0 |
| 1800 | 1797.5 | 17.1 | 289.3 | 3.7 |
| 2000 | 1991.0 | 32.4 | 381.9 | 4.0 |
| 2200 | 2137.4 | 60.3 | 649.7 | 78.0 |
| 2400 | 1978.8 | 94.4 | 1993.9 | 340.7 |

## Run 5 — grpc-jvm, directExecutor + drainNow patch (start 01:29:48Z)

grpc-netty classes replaced with the `wedge/server-drain-now-1.83.1` build
(v1.83.1 + server-side `WriteQueue.drainNow()` in
`NettyServerHandler.channelReadComplete`); everything else identical to run 4,
same node.

| offered | delivered | p50ms | p99ms | dropped/s |
|---|---|---|---|---|
| 200 | 203.9 | 2.9 | 2125.2 | 0.0 |
| 400 | 400.3 | 2.6 | 9.6 | 0.0 |
| 600 | 600.6 | 2.7 | 18.4 | 0.0 |
| 800 | 799.9 | 2.9 | 32.4 | 0.0 |
| 1000 | 1000.8 | 3.4 | 49.0 | 0.0 |
| 1200 | 1196.3 | 4.0 | 66.6 | 0.0 |
| 1400 | 1397.7 | 5.6 | 260.1 | 1.1 |
| 1600 | 1594.6 | 10.1 | 222.0 | 5.4 |
| 1800 | 1796.0 | 20.3 | 230.7 | 1.0 |
| 2000 | 1986.6 | 43.5 | 391.6 | 6.5 |
| 2200 | 2116.4 | 75.5 | 498.1 | 77.4 |
| 2400 | 2013.4 | 104.5 | 717.1 | 318.1 |

## Heap (per-minute /metrics sampling across runs 1–4)

| arm | idle | peak under saturation |
|---|---|---|
| grpc-native | 6 MB | 81 MB |
| grpc-jvm | 11 MB | 61 MB |
| rest | 30 MB | 267 MB |
