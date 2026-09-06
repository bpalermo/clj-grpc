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
Open loop, one `nighthawk_client` per 110 s step, tagged 120 s warmup step
first. CPU per request from the arm's cgroup `cpu.stat` delta over the step's
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

Versions: clj-grpc <fill>, Pedestal 0.8.1 / Jetty 12.0.29, grpc-java 1.83.1 /
Netty 4.2.16.Final, Nighthawk fork `<sha>`.

## Phase A — transport: `rest-h1` (R1, R5) vs `rest-h2c` (R2, R6)

_pending the Nighthawk P0 image_

## Phase B — protocol: `rest-h2c` vs `grpc-jvm` unary (R3, R7)

_pending P1 (gRPC unary)_

## Phase C — interaction model: unary vs `grpc-jvm` stream (R4, R8)

_pending P2 (bidi streaming); cross-checked against the Clojure driver_
