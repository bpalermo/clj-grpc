# Executor grid — 2026-08-29

Four 30-minute scenarios at a fixed 200 req/s per arm ({gRPC, REST} ×
{default threading, flipped threading}), identical 1-CPU/1-Gi pods, one arm
per worker. ~4.6M requests, zero check failures.

**Fixed-rate grid** (30 min per scenario, 200 req/s per arm, ~4.6M requests,
zero check failures): the executor choice is a CPU/median-vs-tail trade —
gRPC `:executor :direct` cut CPU 35% (386→269m native, 221→145m JVM) and won
every p50, but roughly doubled p99 at this low utilization (event-loop
convoying with deferred flushes); Jetty's virtual-thread dispatch cost
nothing on any axis (one dispatch per request either way). Memory was
invariant to threading everywhere. GC logs showed 1-CPU pods get Serial GC
by JVM ergonomics; native's live heap ran ~9 MB.

Operational lessons baked into these manifests: liveness probes need
saturation tolerance (a pegged 1-CPU pod starves its probe and the kubelet
kills a healthy server — measured twice); the k6-operator initializer
inherits the runner's nodeSelector and resources, so an unschedulable
runner spec deadlocks silently in `initialization`; JVM arms need their
first ramp minutes excluded as JIT warmup, native arms don't.
