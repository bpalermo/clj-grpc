# Soak & campaign results — index

On-cluster measurements of the soak arms (gRPC native-image, gRPC JVM,
REST/Pedestal — identical 1-CPU/1-Gi Guaranteed pods on talos-main, one arm
per worker; harness and procedure in [`../soak/README.md`](../soak/README.md)).
Each entry links the full results file and states its conclusion.

## [Executor grid](results/2026-08-29-executor-grid.md) — 2026-08-29

**Conclusion:** at low utilization the executor choice is a CPU/median-vs-
tail trade — gRPC `:executor :direct` cuts CPU 35% and wins every p50 but
roughly doubles p99 (event-loop convoying with deferred flushes), while
Jetty's virtual-thread dispatch costs nothing on any axis and memory is
invariant to threading everywhere. Zero failures in ~4.6M requests.

## [Unary capacity ramps](results/2026-08-29-capacity-raw.md) — 2026-08-29/30

**Conclusion:** max sustainable goodput per identical 1-CPU pod — gRPC-JVM
`:direct` ~2,140 req/s, VT ~2,060, native ~1,550, REST ~960 followed by
queue-death (no admission control). At equal resources gRPC sustains 2.2×
(JVM) / 1.6× (native) REST's throughput, and the executor trade inverts
above ~75% utilization: `:direct` wins goodput *and* tails. A prototype
grpc-netty `drainNow()` patch cut deep-saturation p99 64%
([grpc-java#13012](https://github.com/grpc/grpc-java/issues/13012)).

## [Streaming capacity](results/2026-08-30-streaming-raw.md) — 2026-08-30

**Conclusion:** persistent bidi echo streams move ~15,000–16,000 msg/s on
one core — ~7.5× the unary gRPC plateau and ~16× REST — with p50 <2 ms
through 8,000 msg/s. The executors split only near saturation, where
`:direct` holds p99 2–2.5× lower and keeps delivering at 16k: the per-
message dispatch is the one cost streaming cannot amortize on the VT
executor. The full doctrine across every measured regime: **VT wins only
low-utilization unary tails; `:direct` wins high-load unary, all streaming,
capacity, and CPU — provided handlers never block.**

## [Switch ladder](results/2026-09-switch-ladder-raw.md) — September 2026, in progress

**Question:** what does an existing REST service gain from each switch it
could make — transport (HTTP/1.1 → h2c), protocol (REST/JSON → gRPC unary),
interaction model (unary → stream) — with every adjacent pair of arms
differing in exactly one thing, on one instrument (Nighthawk), at two payload
sizes, with CPU attribution from the arms' own cgroup counters and Pyroscope.
**Conclusion so far (Phase A, transport, 2026-09-06):** HTTP/1.1 → h2c on the
same Pedestal/Jetty service buys nothing in capacity — both saturate the core
at ~925 rps (tiny) / ~750 rps (1.3 KB JSON) — costs 3–16% more CPU per
request below the knee, improves p99 by 10–30% on the realistic body, and
admits ~6–9% more at the knee. Under overload h2c is worse: with thousands of
streams parked at the server it has no flat plateau on 1 KB bodies (517/s and
p99 40 s at 1,200 offered vs h1's steady ~750/s), because unserved work sits
inside the server instead of failing at the client. **Phase B (protocol, 2026-09-07):** h2c → gRPC unary on the same core is
where the gain is — 6× capacity on a 1 KB body (knee 600 → 4,000 rps,
plateau ~750 → ~4,600) and > 8× on tiny (the arm still at 0.76 core when the
single-worker driver ran out at 8,000), CPU per request 3× lower at the same
offered rate, p99 an order of magnitude lower below REST's knee, and a
graceful plateau under the client queue that collapsed h2c. August's k6
"knee" at ~2,140 was the driver; the server's unary capacity is 2–4× higher.
Phase C (streams) pending the Nighthawk fork's P2.
