# Soak & campaign results — index

On-cluster measurements of the soak arms (gRPC native-image, gRPC JVM,
REST/Pedestal — identical 1-CPU/1-Gi Guaranteed pods on talos-main, one arm
per worker; harness and procedure in [`../soak/README.md`](../soak/README.md)).
Each entry links the full results file and states its conclusion. Everything is
1-CPU unless an entry says otherwise — the two-cores and connection-sweep
entries ran at 2 CPU, which is where per-core figures stop being the whole
story.

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

## [Switch ladder](results/2026-09-switch-ladder-raw.md) — 2026-09-06/09

**Question:** what does an existing REST service gain from each switch it
could make — transport (HTTP/1.1 → h2c), protocol (REST/JSON → gRPC unary),
interaction model (unary → stream) — with every adjacent pair of arms
differing in exactly one thing, on one instrument (Nighthawk), at two payload
sizes, with CPU attribution from the arms' own cgroup counters and Pyroscope.

### Where it landed

Per core, 1-CPU pods, `:direct` executor, 1 KB protobuf / 1.3 KB JSON bodies.
These are the re-baselined numbers (agent-free images, compiled codec); the
phase-by-phase figures they replaced are under "the record" below.

| rung | switch | capacity per core | vs REST | migration cost |
|---|---|---|---|---|
| 0 | REST HTTP/1.1 | ~750 rps | — | — |
| 1 | → h2c | ~750 rps | 1× | a server config flag; clients must speak h2c |
| 2 | → gRPC unary | ~4,700 rps | 6× | new clients, protobuf schema, serialization; API shape unchanged |
| 3 | → gRPC stream | ~10,000 msg/s | 13× | API contract: persistent connections, ordering, backpressure |

Rung 1 is free and worthless. **Rung 2 is where the money is** — a service at
REST's knee today frees ~85% of its cores at the same load. Rung 3 adds 1.8×
on top of rung 2 (3× on tiny bodies) for a contract change.

**The caveat that governs rungs 2 and 3: those are per-core numbers, and a
gRPC arm only reaches them if the client opens enough connections.** A
connection binds to one event loop, and under `:direct` that loop also runs
the handler, so a client holding one multiplexed connection to a multi-core
pod uses one core of it — measured flat at 0.79–0.92 cores on a two-core arm
however hard it was pushed. A second connection took that arm from 15,294 to
22,866 msg/s; further connections bought nothing and cost 13% more CPU. Size
clients by connection count as well as pod cores, and assert the count from
`upstream_cx_total` rather than computing it from flags.

### Levers on top of the ladder

Each measured separately on the gRPC arms, all independent of the rung:

- **Direct linking** (`-Dclojure.compiler.direct-linking=true` on the arm's
  JVM): 5–13% of CPU per request, 3–17% per streamed message, `Var.getRawRoot`
  from 3.2% of samples to 1.1%. One property on an unchanged image, and it
  reaches code rules_clj's build-time `direct_linking` cannot — clj-protobuf
  ships to Clojars as source, so its codec is compiled at load time and an
  ahead-of-time caller may not link into it at all. The two linking levers
  cover disjoint code; neither covers both.
- **clj-protobuf's descriptor-compiled codec**: 6–17% CPU (more on streams,
  more under VT), protobuf from 26% of samples to 2%, syscalls then the top
  cost at 35%.
- **protoc-gen-clojure `interop=true`** (0.6.0, typed reads and writes): a
  latency-for-CPU trade, not the ceiling it was aimed at — 3–8% more CPU on
  unary, level on streaming, 15–45% lower p50 throughout.
- **Executor**: the library's default virtual threads cost 15–27% more CPU
  per request and ~25% stream capacity against `:direct`, p50 roughly double.
  The gap *widens* with cores rather than closing.

Not levers: protobuf-java 4.36.1 vs 4.35.1, Netty leak detection, pinning the
VT scheduler to one carrier — each ≤ 4% or nil.

### Where the cost goes

**Attribution (Pyroscope, agent overhead measured at +1–6% CPU):** REST's extra
~1.3 ms per request is not JSON (0.06 ms) but the Pedestal/Clojure request
pipeline (0.41 ms of persistent maps, Vars and seqs, plus 0.24 ms of Java
collections and locks), Jetty (0.17 ms) and 8× the syscall time of gRPC
(per-connection `writev` and thread-pool hand-offs vs one multiplexed socket
on an event loop). On the gRPC arms the largest software cost, 20–26%, is
protobuf's descriptor-driven field access under the clj-protobuf codec, which
the typed `interop=true` path removes; streaming's gain shows as grpc-java
shrinking from 8% to 4% of samples.

### Open

Why a gRPC streaming arm stops at ~25,000 msg/s with half a core idle and no
quota pressure. Four things are now ruled out rather than suspected: the codec
(an interop arm touching neither of clj-protobuf's monitors hits the same wall),
the connection count (2, 4 and 8 connections land within 5% of each other), CPU
saturation (cores sit at 1.47–1.64 of 2.0), and the driver (0.60–0.66 of its
single core throughout). What remains: HTTP/2 flow-control windows — the server
never sets `flowControlWindow`, so grpc-java's default and BDP auto-tuning
apply — the 40 × 256 in-flight budget, a serialization point inside the
transport, or a wakeup path. Separating those needs a wall-clock profile;
`process_cpu` is the only profile type Pyroscope holds for these arms and it
cannot see a parked thread.

### The record

**Phase A (transport, 2026-09-06):** HTTP/1.1 → h2c on the
same Pedestal/Jetty service buys nothing in capacity — both saturate the core
at ~925 rps (tiny) / ~750 rps (1.3 KB JSON) — costs 3–16% more CPU per
request below the knee, improves p99 by 10–30% on the realistic body, and
admits ~6–9% more at the knee. Under overload h2c is worse: with thousands of
streams parked at the server it has no flat plateau on 1 KB bodies (517/s and
p99 40 s at 1,200 offered vs h1's steady ~750/s), because unserved work sits
inside the server instead of failing at the client.

**Phase B (protocol, 2026-09-07):** h2c → gRPC unary on the same core is
where the gain is — 6× capacity on a 1 KB body (knee 600 → 4,000 rps,
plateau ~750 → ~4,600) and ~11× on tiny (knee ~10,500 rps per core, by a
two-worker cross-check; the ladder's own tables stop at 8,000 with the arm at
0.76 core), CPU per request 3× lower at the same offered rate, p99 an order of
magnitude lower below REST's knee, and a graceful plateau under the client
queue that collapsed h2c. August's k6 "knee" at ~2,140 was the driver; the
server's unary capacity is 2–4× higher.

**Phase C (interaction model, 2026-09-07):** unary → persistent bidi streams
buys 1.8× more on 1 KB messages (knee ~6,500, plateau ~8,200 msg/s per core,
bounded by the `:direct` event loop at 0.87 core, never the quota) and ~3×
on tiny (> 31,500 msg/s, arm at 0.82 core), at 15–25% less CPU per message,
p50 ≤ 3 ms to the knee, a flat plateau under any overload with zero errors.
August's 7.5×/16× streaming ratios were the k6 driver under-measuring unary;
on one instrument they are 1.8×/11×.

**Re-baseline (2026-09-07/08) — supersedes the Phase B and C capacities
above.** Measured again with the library's default virtual-thread executor
(grpc-java will not optimise `:direct` further), agent-free images and
clj-protobuf's descriptor-compiled codec, the gRPC rows were understated:
`:direct` streaming reaches **~10,000 msg/s per core at 0.085 ms/msg** (was
~8,200 at 0.107) and unary ~4,700 at 0.177 ms, giving the table at the top of
this section. Most of the 40–70% VT penalty the older images showed was a
JVMTI agent loaded even when disabled; the real figure is 15–27%.

**Typed reads (2026-09-08/09, protoc-gen-clojure 0.6.0):** with `interop=true` now
typing reads as well as writes, it costs 3–8% more CPU than the compiled codec on
unary and is level on streaming (1–4%, inside the noise), while returning 15–45%
lower p50 throughout — two independent pairs, since the effect is noise-sized. The
frames show why: the typed path moves conversion work out of the codec into protoc's
generated accessors almost one for one (streaming self time 9.4% → 3.2% codec,
3.0% → 9.1% protobuf-java, sum unchanged).

**Two cores (2026-09-09):** capacity follows CONNECTIONS, not cores — but only up to a
point; the connection sweep below found where the climb stops. `:direct`
streaming sits flat at 0.78–0.90 cores across the whole ramp on a two-core pod —
the second core idle — because a stream's connection binds to one event loop and
`:direct` runs the handler on it; unary, whose pool grows to eight connections under
load, reaches 1.33. Virtual threads do spread a single connection (1.12 → 1.57 cores) and
still lose, spending 1.57 cores for 14,438 msg/s where `:direct` spends 0.90 for
15,250; the executor gap widens with cores (44–80% more CPU) rather than closing.
Scaling is 1.2–1.6x, not 2x, and for streaming the gain is GC moving off the request
path rather than parallel service.

**Connection sweep (2026-09-09):** the deliberate experiment the Two cores entry called for,
now that chart 0.2.19 lets `--max-concurrent-streams` reach the streaming branch. One
`:direct` arm on two cores, 40 streams, `--concurrency 1`, only the connection count
moving (1/2/4/8, asserted from `upstream_cx_total`). **One connection is flat at
0.79-0.92 cores however hard it is pushed** — the second core simply cannot be reached.
**Capacity then stops climbing at two connections:** 15,294 msg/s at 0.92 cores with one,
22,866 at 1.49 with two, and then nothing more — 4 and 8 connections deliver within 2% of
what 2 delivers while burning 13% more CPU (1.67-1.69 cores) and taking throttling for it.
Two connections is both the ceiling and the cheapest way to reach it (15,390 msg/s per core
against 13,340 at four). **The ceiling is NOT a CPU ceiling and stays unexplained:** the
two-connection arm holds it with half a core idle and 0.1 s throttled, so "two connections"
matching "two cores" is a coincidence on this evidence, not a mechanism. The driver held
0.49-0.64 of its one core throughout, so no step was client-limited.

**Reading any ramp in these results:** the first step after warmup under-reads
by ~10% (same arm, same connections, 19,598 msg/s when reached through a ramp
vs 17,731 as a ramp's first step, with 4× the server-side throttling). Take
plateaus from the upper steps, never the first. Five minutes at 200 rps does
not warm a 1-CPU JVM for 20,000. That warmup is a deployment concern — warm
before serving — rather than a protocol one, and it is not on the ladder.

**A shared monitor on the encode path (2026-09-09):** the clj-protobuf session
found two process-wide `Collections.synchronizedMap`s on the compiled codec's
per-message path (one from `.build` via `initialized?`, one the parser registry
on decode); `synchronizedMap` locks reads, so a cache hit still serializes, and
`.build` scales **0.31x** from 1 to 8 threads where `.buildPartial` scales
cleanly. Tested on-cluster as a paired A/B — same session, same chart, both
arms pinned to `:direct` (the chart leaves the interop arm on virtual threads,
which would have made the executor a second variable). **Interop is 10-14%
cheaper per message at every step and peaks 9.7% higher** (25,287 vs 23,051
msg/s), and the banked profiles show the call chain present in one arm and
absent in the other: `SynchronizedMap.get` 0.85% of CPU on the compiled arm,
nil on interop, where protoc's own `Item.isInitialized` does the same check for
0.04%. Corroborated by a sign flip — at 1 CPU the same images measured interop
3-8% DEARER, and contention cannot exist on one core. **But the monitor is a
cap, not the ceiling:** interop plateaus at ~25,000 with 1.47 of 2 cores and
half a core idle while touching neither monitor. Measured against clj-protobuf
0.2.2; the fix is upstream as `3ce5ed7` (clj-protobuf #40) but was unreleased
at the time, so this is a before-number and the gap should shrink toward the
1-4% the 1-CPU runs showed once 0.2.5 ships.

**The ceiling is global (2026-09-09):** the monitor A/B left the interop arm
plateauing at ~25,000 msg/s on 1.47 of 2 cores while touching neither
`synchronizedMap`, so the monitor was a cap and not the ceiling. Tested by
transposing the sign-flip instrument from cores to connections — cores are the
cleaner variable and no node here has three free for one arm; the idle
half-core is what makes connections discriminate, since a per-connection bound
would climb with spare CPU available and a global one would not. **Flat: peaks
of 25,287 / 25,428 / 24,224 msg/s at 2 / 4 / 8 connections**, a spread of −4.2%
to +0.6%, at 1.47 / 1.63 / 1.60 cores, driver at 0.60–0.66 of one core
throughout. Predicted in advance (flat at ~25,000, cores 1.6–1.7) with
thresholds fixed before the runs, including the one that matters most — cores
above 1.85 would have read as plain CPU saturation rather than a serialization
point. So the compiled arm's flatness across connections was never the monitor;
the monitor only set the level ~10% lower. Adding connections past two is worse
than useless: 2 → 4 bought 0.6% for 11% more CPU, 4 → 8 lost 4%.
