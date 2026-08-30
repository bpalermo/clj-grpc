# Soak & capacity campaign results

On-cluster measurements of the three soak arms (gRPC native-image, gRPC JVM,
REST/Pedestal — identical 1-CPU/1-Gi pods on talos-main, one arm per worker;
harness and procedure in [`../soak/README.md`](../soak/README.md); raw
per-step tables in [`../soak/results/`](../soak/results/)).

### 2026-08-29/30 — executor grid + capacity campaign

**Fixed-rate grid** (30 min per scenario, 200 req/s per arm, ~4.6M requests,
zero check failures): the executor choice is a CPU/median-vs-tail trade —
gRPC `:executor :direct` cut CPU 35% (386→269m native, 221→145m JVM) and won
every p50, but roughly doubled p99 at this low utilization (event-loop
convoying with deferred flushes); Jetty's virtual-thread dispatch cost
nothing on any axis (one dispatch per request either way). Memory was
invariant to threading everywhere. GC logs showed 1-CPU pods get Serial GC
by JVM ergonomics; native's live heap ran ~9 MB.

**Capacity ramps** (200→2400 req/s, 2-min steps, one arm at a time, same
pods): max sustainable goodput per identical 1-CPU pod —

| configuration | plateau | overload behavior |
|---|---|---|
| gRPC JVM `:direct` | ~2,140 req/s | graceful (h2 flow-control backpressure) |
| gRPC JVM VT default | ~2,060 req/s | graceful, tails degrade |
| gRPC native VT | ~1,550 req/s | liveness-starvation kills before probe tuning |
| REST (Jetty, HTTP/1.1) | ~960 req/s | queue-death: p50 pins at 1.7 s, then collapse |

At equal resources gRPC sustains **2.2× (JVM) / 1.6× (native)** REST's
throughput. The executor trade **inverts with load**: VT protects tails ~2×
at ~10% utilization; above ~75% utilization `:direct` wins goodput *and*
tails (p99 382 vs 662 ms at 2000 req/s). Peak heaps under saturation:
native 81 MB, gRPC-JVM 61 MB, REST 267 MB.

A grpc-netty patch adding a server-side `WriteQueue.drainNow()` (upstream
prototype) reproduced its JMH signature on-cluster: p99 −20–23% across
1200–2200 req/s and **−64% at 2400** (1994→717 ms), goodput unchanged.

**Streaming capacity** (2026-08-30, bidi Chat echo, same grpc-jvm pod, raw
tables in `results/`): streaming moves **~15,000–16,000 msg/s on one core**
— ~7.5× the unary gRPC plateau and ~16× REST — with per-message p50 under
2 ms and p99 under 30 ms through 8,000 msg/s. The executors split decisively
at streaming's limits: `:executor :direct` holds p99 2–2.5× lower than the
VT default at every rate above 6,000 and keeps delivering at 16,000
(38 deferred/s vs VT's 625/s) — the per-message dispatch the VT executor
still pays is the one cost streaming cannot amortize. Full doctrine across
every measured regime: **VT wins only low-utilization unary tails; `:direct`
wins high-load unary, all streaming, capacity, and CPU — provided handlers
never block.** And the library's oldest guidance is now cluster-quantified:
one stream really does beat N unaries, by ~7.5× per core.

Operational lessons baked into these manifests: liveness probes need
saturation tolerance (a pegged 1-CPU pod starves its probe and the kubelet
kills a healthy server — measured twice); the k6-operator initializer
inherits the runner's nodeSelector and resources, so an unschedulable
runner spec deadlocks silently in `initialization`; JVM arms need their
first ramp minutes excluded as JIT warmup, native arms don't.
