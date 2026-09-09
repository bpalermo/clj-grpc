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

## [Switch ladder](results/2026-09-switch-ladder-raw.md) — 2026-09-06/07

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
plateau ~750 → ~4,600) and ~11× on tiny (knee ~10,500 rps per core, by a
two-worker cross-check; the ladder's own tables stop at 8,000 with the arm at
0.76 core), CPU per request 3× lower at the same
offered rate, p99 an order of magnitude lower below REST's knee, and a
graceful plateau under the client queue that collapsed h2c. August's k6
"knee" at ~2,140 was the driver; the server's unary capacity is 2–4× higher.
**Phase C (interaction model, 2026-09-07):** unary → persistent bidi streams
buys 1.8× more on 1 KB messages (knee ~6,500, plateau ~8,200 msg/s per core,
bounded by the `:direct` event loop at 0.87 core, never the quota) and ~3×
on tiny (> 31,500 msg/s, arm at 0.82 core), at 15–25% less CPU per message,
p50 ≤ 3 ms to the knee, a flat plateau under any overload with zero errors.
**The ladder, per core on 1 KB bodies: REST h1 ~750 → h2c ~750 → gRPC unary
~4,600 (6×) → stream ~8,200 (11×).** August's 7.5×/16× streaming ratios
were the k6 driver under-measuring unary; on one instrument they are
1.8×/11×. Rung 2 is where the money is; rung 1 is free and worthless;
rung 3 is a contract change for 1.8×. **Attribution (Pyroscope, agent
overhead measured at +1–6% CPU):** REST's extra ~1.3 ms per request is not
JSON (0.06 ms) but the Pedestal/Clojure request pipeline (0.41 ms of
persistent maps, Vars and seqs, plus 0.24 ms of Java collections and locks),
Jetty (0.17 ms) and 8× the syscall time of gRPC (per-connection `writev`
and thread-pool hand-offs vs one multiplexed socket on an event loop). On
the gRPC arms the largest software cost, 20–26%, is protobuf's
descriptor-driven field access under the clj-protobuf codec, which the
typed `interop=true` path (protoc-gen-clojure 0.5.1) removes; streaming's
gain shows as grpc-java shrinking from 8% to 4% of samples.
**Typed reads (2026-09-08/09, protoc-gen-clojure 0.6.0):** with `interop=true` now
typing reads as well as writes, it costs 3–8% more CPU than the compiled codec on
unary and is level on streaming (1–4%, inside the noise), while returning 15–45%
lower p50 throughout — two independent pairs, since the effect is noise-sized. The
frames show why: the typed path moves conversion work out of the codec into protoc's
generated accessors almost one for one (streaming self time 9.4% → 3.2% codec,
3.0% → 9.1% protobuf-java, sum unchanged). So interop is a latency-for-CPU trade on
unary and a free latency win on streaming, not the ceiling the codec was aimed at.
**Re-baseline (2026-09-07/08):** measured again with the library's default
virtual-thread executor (grpc-java will not optimise `:direct` further),
agent-free images and clj-protobuf's descriptor-compiled codec, the gRPC
rows were understated: `:direct` streaming reaches **~10,000 msg/s per core
at 0.085 ms/msg** (was ~8,200 at 0.107) and unary ~4,700 at 0.177 ms, so the
ladder reads REST h1 ~750 → h2c ~750 → unary ~4,700 (6×) → stream ~10,000
(13×). The VT default costs 15–27% more CPU per request and ~25% stream
capacity with p50 roughly double — not the 40–70% the older images showed,
most of which was a JVMTI agent loaded even when disabled. The compiled
codec is worth 6–17% CPU (more on streams, more under VT) and takes protobuf
off the hot path (26% of samples → 2%, syscalls now the top cost at 35%);
protobuf-java 4.36.1 vs 4.35.1, Netty leak detection and pinning the VT
scheduler to one carrier are each ≤ 4% or nil.
**Direct linking (2026-09-08):** `-Dclojure.compiler.direct-linking=true` on the
arm's JVM is worth 5–13% of CPU per request and 3–17% per streamed message, with
`Var.getRawRoot` falling from 3.2% of samples to 1.1% and p50 improving at every
matched step — one property on an unchanged image. It works where rules_clj's
build-time `direct_linking` attribute cannot: clj-protobuf ships to Clojars as
source, so its codec is compiled by Clojure at load time, and an ahead-of-time
caller may not link into it at all. The two levers cover disjoint code and
neither covers both.
