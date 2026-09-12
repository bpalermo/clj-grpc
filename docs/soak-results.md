# Soak & campaign results

What on-cluster measurement has established about running Clojure gRPC and
REST services, stated as conclusions. The reasoning that produced them, the
corrections along the way, and the runs that were superseded are in git
history; this file is what is currently true.

Measurements are from talos-main: Raspberry Pi CM5 on a DeskPi Super6C, 4 cores
and 8 GB per node, flannel VXLAN, one arm per worker. Harness and procedure in
[`../soak/README.md`](../soak/README.md); raw per-step tables under
[`results/`](results/).

## Conclusion

Two questions, one answer each, on the shape a Knative or Kubernetes pod
actually gets: one CPU, ~1 KB bodies, a client that keeps its connections.

### REST or gRPC

**gRPC unary gives a Clojure REST service about 8× the requests per core, and
streaming about 17× the messages, with the API shape intact for unary.** The
gain is not the encoding: JSON is 0.06 ms of REST's ~1.5 ms per request. It is
the request pipeline — Pedestal's persistent maps and interceptors, Jetty's
thread hand-offs, and 8× the syscall time of a multiplexed socket on an event
loop. So a REST service cannot close the gap by swapping its codec; it closes it
by changing the framework and the connection model, which is what a gRPC
migration is. Below REST's knee gRPC's p99 is an order of magnitude lower, and
under overload it plateaus and sheds at the client, where h2c and native both
park work inside the server and let p50 run to seconds.

**h2c is not a step on the way.** Same capacity, 3–16% more CPU per request,
worse overload behaviour; a REST service should not migrate to it, and a gRPC
migration gets HTTP/2 for free.

**Streaming is a second, independent ~2×** for a service whose contract can
change: persistent streams, ordering and backpressure become the API. It is
grpc-java's per-RPC setup amortized away — the message itself costs the same.

**Both figures are per core and per connection.** One connection binds to one
event loop and, under `:direct`, runs the handler on it; a single-connection
client gets one core of a four-core pod. Capacity planning is CPU quota *and*
client connection count.

### JVM or native

**For a service that carries traffic, the JIT-compiled JVM. For a process
whose life is measured in seconds or whose pods sit idle, native.** On the
cluster, same pod, same bodies, same driver, the native image delivers 0.34× the
JVM's unary requests and 0.29× its streamed messages, at 3–4× the CPU each —
one JVM pod does the work of three native ones — in about half the memory
(40–106 MB against 150–190 MB at the knee). The gap is the platform, not the payload — removing the
body (7-byte tier) leaves native at 0.23–0.38× the JVM. Its one decisive win is
cold start:
79 ms to first RPC against the JVM's ~1,750 ms on the loopback bench, 22×.
Under overload it behaves like h2c, not like the JVM: p50 climbs to 1.7–2.5 s
and `/metrics` stops answering.

**That reverses the loopback picture, and the reason is the payload.** The
loopback comparison in the README — a wash at 1–2 cores, native cheaper per
call — was an 8-byte echo over a Unix socket on x86, a shape where per-message
framing is the whole cost. The cluster runs 1 KB bodies over TCP on arm64, where
two thirds of the gRPC arm's CPU is payload handling, and that is the work the
JIT is good at and the closed-world image is not. Payload, transport and
platform changed together between the two, so the split between them is
unmeasured; a native tiny-tier run on the cluster would separate payload from
platform. Until it exists, **the sizing number for a production-shaped service is
the cluster one**, and the loopback number describes ping-sized RPCs.

**Native numbers are per image.** GraalVM's output is not reproducible, so the
chart does not pin it and a native figure compares only within one named digest.
The JVM ladder has a measured replicate floor of 0.1–4%; the native arm has one
run per mode.

### Virtual threads or `:direct`

**`:direct` for a 1-CPU pod or a many-connection client; virtual threads for a
single-connection client on a multi-core pod.** Measured off-cluster on a pinned
x86 host, because the cluster cannot host it (below, "The executor, and
cores"). One connection under `:direct` never uses more than one core, at any
core count. Virtual threads spread that one connection across the cores it has
— 2.5× `:direct`'s single-connection ceiling at 4 cores — and that is the shape
a sidecar mesh or a single upstream channel produces. Given eight connections,
`:direct` scales too and delivers ~1.7× the virtual-thread throughput on the
same cores at half the CPU per message — and every connection added to a
virtual-thread server costs it throughput (one 237k, two 203k, eight 163k at
4 cores), so the two executors want opposite client shapes. Two defaults cap
both executors on this host: the JVM's Serial young generation (~5 MB under a
1 GB limit) and grpc-java's one-credit-per-message flow control; sizing the
first and batching the second lifts virtual threads to ~445,000 msg/s on one
connection and `:direct` to ~487,000 on eight, and closes most of the gap
between them. Virtual threads cost 11–25% more per
streamed message and 30–65% more per unary request on every shape; on
streaming that cost is flat with cores, on unary it grows 1.5× from one core
to four. The safety rule is unchanged: a handler that blocks on `:direct`
stalls every connection on its loop.

### What would change this

- ~~A native tiny-tier run on the cluster~~ — done 2026-09-12: the gap is the
  platform (below). What would still move it is PGO or G1 on native.
- PGO or G1 on native, which needs Oracle GraalVM, whose current builds fail
  every RPC; the CE image here ran Serial GC with `-Xmx512m -Xmn256m`.
- The multi-core executor result is x86 and loopback. The cluster cannot
  host it (4-core nodes with ~2.2 cores of headroom); an arm64 host with
  four free cores would say whether the ratios carry.
- ~~The ladder re-run on chart 0.2.23~~ — done 2026-09-12: `:direct` unary
  ~8,300 / streaming ~16,000; virtual-thread streaming ~13,900 (+76%);
  interop on virtual threads ~16,000. Every figure in the ladder table
  predates both defaults and the table itself is the next thing to refresh.
- ~~Typed interop on an unsaturated multi-core host~~ — done 2026-09-12: the
  advantage tracks the mode (streaming −10–22%, unary nil), not the cores. It
  does not change the ordering above.

## The ladder — what a REST service gains from each switch

1 KB protobuf / 1.3 KB JSON bodies, `:direct` executor, each rung differing from
the one below in exactly one thing, measured on one instrument (Envoy
Nighthawk). **Median of three replicates per rung**, chart 0.2.21/0.2.22
(identical arm images), 2026-09-10/11, each on a ramp that runs past the knee,
node CPU sampled at every step to confirm the host was never the limit.

| rung | switch | capacity per 1-CPU pod | replicates | vs REST | migration cost |
|---|---|---|---|---|---|
| 0 | REST HTTP/1.1 | **795 rps** | 795 / 781 / 806 | — | — |
| 1 | → h2c | **800 rps** | 800 / 800 / 800 | 1× | a server config flag; clients must speak h2c |
| 2 | → gRPC unary | **6,540 rps** | 6,541 / 6,618 / 6,358 | **8.2×** | new clients, protobuf schema, serialization; API shape unchanged |
| 3 | → gRPC stream | **14,000 msg/s** | 14,009 / 13,966 / 14,266 | **17.6×** | API contract: persistent connections, ordering, backpressure |

Replicate spread is 0.1–4.0%. The single-run figures these replace (6,700 and
14,400) were each the *best* of their runs, not the middle.

These replace an earlier table reading 750 / 750 / 4,700 (6×) / 10,000 (13×),
which was wrong in two independent ways:

- **Its gRPC ramps stopped at the knee instead of past it** — unary ended at
  4,800 offered with 1.2% shedding, streaming at 10,000 with 0.2%. Neither had
  found a ceiling. Re-running *that same chart* with a longer ramp gives ~5,400
  rather than ~4,700. The REST ramps did run past their knee, so the error was
  one-sided and the ratios were understated.
- **The stack has since got ~9% cheaper per request** (attributed below), which
  near the knee buys more than 9% of capacity.

**Rung 1 is free and worthless.** h2c buys nothing in capacity, costs 3–16% more
CPU per request, improves p99 by 10–30% on realistic bodies — and is *worse*
under overload, because unserved work parks inside the server instead of failing
at the client (517 rps and p99 40 s at 1,200 offered, against h1's steady ~750).

**Rung 2 is where the money is.** A service at REST's knee frees ~85% of its
cores at the same load. p99 drops an order of magnitude below REST's knee, and
the arm plateaus gracefully under the client queue that collapses h2c.

**Rung 3 adds ~2× on top of rung 2** for a different API contract.

**The two gRPC rungs stop for different reasons**, and the difference is
connections. Streaming runs on one connection and holds 0.82–0.85 cores at every
step even when pushed to 20,000 offered with 5,771/s shedding — one event loop,
one core's worth, exactly the cap measured directly at two cores. Unary opens
eight connections under load and reaches 0.94–0.98 cores of its quota. Neither
was host-limited: the node sat at 2.3–3.1 of 4 throughout.

**Two kinds of spread, and they are very different sizes.** Run-to-run spread on
a *shared* ramp is **0.1–4%** (the replicate columns above). Ramp-*shape* spread
is much larger: two runs of the identical chart put the same arm at 5,999 and
5,240 rps at 6,000 offered, the difference being that one climbed there and the
other started there — ~13%. So a comparison across runs is trustworthy to a few
percent only if the ramps match; across ramp shapes, differences under ~15% mean
nothing. An earlier version of this file conflated the two and called the noise
floor ~15%; that overstated it for matched ramps and understated the ramp-shape
effect.

## The native-image arm — deployed, and now measured

GraalVM native-image of the same gRPC server, on the same 1-CPU pod, same
bodies, same driver. Not on the ladder because the chart cannot pin it — GraalVM
is not reproducible, so a chart version cannot stand still around a digest that
moves — which means a native number only compares within one named image. This
one is `soak-grpc-native@sha256:76b6edb7…`, built by CI from main at `4cce8ac`
on 2026-09-11.

| mode | native | JVM (`:direct`, median) | native / JVM |
|---|---|---|---|
| unary, realistic | ~2,240 rps at 0.45–0.49 ms/req | 6,540 at ~0.15 | **0.34×** capacity, ~3× CPU per request |
| stream, realistic | ~4,100 msg/s at 0.24 ms/msg | 14,000 at ~0.06 | **0.29×** capacity, ~4× CPU per message |
| RSS at the knee | 40–106 MB | 150–190 MB | **~0.5×** memory |

Both native runs are pinned at 0.96–1.00 cores with the node at 2.2–3.1 of 4, so
this is the arm's own limit and not the host's. Under overload it degrades the
way h2c does — p50 rises to 1.7 s (unary) and 2.5 s (stream) rather than
shedding at the client — and `/metrics` stops answering at the top step.

**Native buys memory and startup, and pays for it in throughput.** Per request
it is roughly 3–4× dearer than the JIT-compiled JVM at steady state, which is
consistent with what a closed-world AOT compile of a dynamic language gives up.
The August figure of ~1,550 unary on the k6 driver was the driver, not the arm.

**The gap is the platform, not the payload.** Measured 2026-09-12 by running
the same digest and the JVM `:direct` arm on the 7-byte tier the same night:
native delivers 0.38× the JVM's unary requests (~4,700 vs ~12,300 at 1 CPU)
and 0.23× its streamed messages (~11,200 vs ≥47,700), at 2.8× and 4.9× the CPU
each — the same band as the 1 KB tier's 0.34× / 0.29×. Pre-registered: ≤0.45×
on tiny means platform. So the 3–4× is AOT code quality, Serial GC and the
absence of a JIT, and a native-aware codec would not narrow it; the levers
that could are PGO and G1, which need Oracle GraalVM (currently failing every
RPC). Raw tables: `results/2026-09-12-native-tiny/`.

## Connections, not just cores

**A connection binds to one event loop, and under `:direct` that loop also runs
the handler — so one multiplexed connection to a multi-core pod uses one core of
it.** Measured directly: an arm pinned at 0.90 cores across a whole ramp while
the node it ran on had a full core spare.

The ladder's figures are therefore **per core**, and turning them into pod
capacity means sizing the client's connection count as well as the pod's CPU. A
single-connection client against a four-core pod gets one core of it.

More connections raise the ceiling; how far is untested here, because this
hardware saturates before the software does (below). Drive connection count with
`--max-concurrent-streams` — `--connections` is a circuit breaker, and exceeding
it produces `upstream_cx_overflow` rather than more connections — and assert the
result from `upstream_cx_total` rather than computing it from flags.

## The executor, and cores

The cluster's executor comparison is one core, and its 2-core pair was
host-limited, so the question "which executor uses a multi-core pod" was
measured off-cluster, 2026-09-11: an i9-7900X with the server and the driver
pinned to disjoint physical cores (hyperthread siblings idle), same deploy jar
on the same distroless base digest as the cluster image, same bodies, same
Nighthawk fork, the chart's own `run.sh` and `soak/collect.sh` unchanged.
Two executors × 1, 2 and 4 server cores × four client shapes; predictions were
fixed before the first run and are in the results directory with the tables.

**Virtual threads are the only executor that scales one connection across
cores.** Streaming, 40 streams on one connection, msg/s at the knee:

| server cores | `:direct` | virtual threads | VT / direct |
|---|---|---|---|
| 1 | ~123,000 at 0.008 ms | ~66,000 at 0.015 ms | 0.5× |
| 2 | ~92,000 at 0.011 ms (1.0 core) | ~121,000 at 0.015 ms (1.8 cores) | 1.3× |
| 4 | ~94,000 at 0.011 ms (1.0 core) | ~237,000 at 0.013 ms (3.1 cores) | **2.5×** |

Under `:direct` the connection's event loop runs the handler, so one
connection is one thread and the per-CPU samples show one core's worth
migrating across the others. (The same thread does ~123,000 on a dedicated
core and ~92,000 when free to migrate over two or four — the cost of moving.)
Virtual threads hand the work off the loop, and one connection grows with the
cores it has.

**Given connections, `:direct` scales too, and it is cheaper per message at
every core count.** Same streams over eight connections:

| server cores | `:direct` | virtual threads | direct / VT |
|---|---|---|---|
| 1 | ~113,000 at 0.009 ms | ~55,000 at 0.018 ms | 2.1× |
| 2 | ~179,000 at 0.010 ms (1.8 cores) | ~105,000 at 0.018 ms (1.9 cores) | 1.7× |
| 4 | ~268,000 at 0.010 ms (2.7 cores) | ~163,000 at 0.020 ms (3.3 cores) | 1.6× |

**Where the loop's time goes, measured three ways on the same plateau**
(`results/2026-09-12-local-vt-loop/`, one connection, ~372,000 msg/s, the
shipped defaults): the loop runs 0.94 of a core, 0.67 user and 0.27 kernel;
its Java samples are ~49% write path (HTTP/2 encoder, promises, outbound
buffer, iov assembly), ~41% buffer refcount and pool bookkeeping, 7% flow
control and 2% inbound frame decode — grpc-java deframes on the
application thread, so the loop's read side is the kernel. Shrinking the
response from 1 KB to one field takes 2 µs off the message but only 0.2 µs
off the loop. So the loop costs ~2.5 µs per message almost regardless of
bytes: **the lever is message count, not message size** — a service that
batches N items into one streamed message pays the loop once for N, and no
server-side option in this document moves the loop's per-message cost. It
also bounds codec work: every decode improvement lands on the application
thread, so the typed read path's ~0.6 µs and the loop's ~2.5 µs are two
fixed per-message costs of which only the first is the codec's to move.
Once a shape's per-message cost approaches the loop floor, further codec
work cannot help that shape, however much decode time it still shows.

**Two more per-message optimisations in code we own measured nil, and
that is the finding.** After the typed read path, the carrier's largest
owned blocks in the loop-breakdown JFR were the compiled arm's generic
per-field write dispatch (`codec/set-field!` → `map->message`, keyword
lookups and derefs) and grpc-java's marshaller, whose per-thread parse
buffer never hits under a thread-per-task executor. Both were built and
measured as one-variable pairs (`results/2026-09-12-local-typed-write/`,
`results/2026-09-12-local-direct-marshaller/`): a typed write path
(protoc-gen-clojure branch 03c1d8c emitting `codec/slot-set!` per field on
clj-protobuf 0.4.0, byte-identical on clj-protobuf's suite) is within ±1% on
every shape at the knee and up to +7% below it on `:direct` one connection;
clj-grpc's own zero-copy, one-write marshaller is nil on one connection
under both executors and −5% / +7% delivered only on virtual threads with
eight connections. Neither ships. Together with the read path's 4–9% they
say where streaming cost lives on this stack: the read side's per-field
conversion was the one owned per-message cost worth taking, the write
side's is already dominated by the coercion and the field write it cannot
remove, and below those sit the loop and the transport. The next owned
lever is not per-field work.

**And the floor is per message, not per frame.** With the Nighthawk fork
coalescing 25 client messages per inbound DATA frame
(`results/2026-09-12-local-client-coalescing/`), the loop drops from 0.96
to 0.88 of a core — all user time, the kernel share unchanged — and the
message costs 9% less at the top step, for 3–5 ms of added p50. The
inbound frame count is ~10–15% of the loop's cost; the rest is the server's
one-frame-per-response write side, which no client-side setting can reach
— so this experiment also bounds what a client can do for the server's
loop at roughly a tenth. Client-side coalescing is a latency-for-CPU trade
worth ~9% at best, not a recommendation; batching at the message level pays
the loop once for N on both sides. (Harness note: `--stream-batch-messages`
is a ceiling; at 40 streams the per-stream arrival rate means the flush
interval is what sets the achieved batch, which is why it is read from the
counter.)

**And for virtual threads, connections cost.** Same 4 cores, streaming: one
connection ~237,000 msg/s at 0.013 ms, two ~203,000 at 0.015, four ~187,000 at
0.017, eight ~163,000 at 0.020 — monotonic, the mirror image of `:direct`,
which goes from ~92,000 on one connection to ~268,000 on eight. So the two
executors want opposite client shapes: virtual threads one connection,
`:direct` as many as the client can give. With the shipped defaults and two
loops the loss is gone but not reversed: two connections deliver ~377,000
against ~358,000 on one (+5%, at the floor) at the same cost per message,
so one connection is still enough for a virtual-thread server and a second
no longer hurts.

**Virtual threads' cost per streamed message does not grow with cores** —
0.015 / 0.015 / 0.013 ms on one connection, 0.018 / 0.018 / 0.020 on eight. That
retracts the cluster's 2-core reading of "~2× per message", which was the node
(both arms were host-limited there, before the node sampler existed). At
matched rates below the knee virtual threads cost 11–25% more per streamed
message and 30–65% more per unary request, wider than the cluster's 15–27%.

**Unary follows streaming, with one difference.** Eight connections, rps at
the knee:

| server cores | `:direct` | virtual threads | direct / VT |
|---|---|---|---|
| 1 | ~51,500 at 0.019 ms | ~40,600 at 0.025 ms | 1.3× |
| 2 | ~94,000 at 0.020 ms (1.9 cores) | ~61,000 at 0.031 ms (1.9 cores) | 1.5× |
| 4 | ~136,000 at 0.024 ms (3.3 cores)* | ~92,500 at 0.038 ms (3.5 cores) | 1.5× |

*\* the driver was at 6.2 of its 8 logical CPUs there, so the 4-core `:direct`
knee is shared with the client and is a floor.* On one connection at one core
`:direct` does ~58,000 at 0.016 ms against ~39,000 at 0.026; at two cores both
carried 50,000, `:direct` on 1.05 cores and virtual threads on 1.65.

The difference: **on unary, virtual threads' cost per request does grow with
cores** — 0.025 → 0.031 → 0.038 ms from one to four, 1.5×, where on streaming
it stayed flat. Pre-registered as a fail at ≥ 1.5×, and it landed on the
line. A unary RPC is one virtual thread mounted and unmounted per request; a
streamed message amortises that over the stream.

One client-side finding came with it: with 512 streams allowed per
connection, the client pool piles work onto the first connection and opens the
rest only when it is full, so `:direct` unary at 4 cores with connections "as
needed" plateaued at ~59,000 on 2.2 cores. Connection count is the client's
policy, not the server's property, and a `:direct` server's capacity is set by
it.

**Caveats that bound what this says.** It is x86 and loopback: per message at
the knee this host is 6–8× cheaper than the CM5, so only within-host ratios
carry to the cluster. Rows where the driver was at its limit are labelled in
the tables and not read as knees (the single-worker unary rows at 2 and 4
cores). Virtual threads stopped at ~3.1 of 4 cores on one connection, and a
per-thread read of the server at that plateau says why: the connection's
event loop is the busiest thread at 82%, the four carriers sit at 52% each,
nothing is saturated — one connection's inbound path still runs through one
loop, and that is the ceiling virtual threads reach, not the cores. `:direct`
with eight connections at 4 cores plateaued at ~278,000 with both sides under
75%; quadrupling the client's in-flight budget moved it 5%, and the cause
turned out to be the garbage collector's default young generation (next
section): with one sized, the same shape delivers ~487,000 on 3.9 cores.

### Levers for the virtual-thread executor — and one that moves both

Measured 2026-09-12 on the same pinned host, one lever at a time against a
same-session baseline and then stacked; predictions fixed before each phase.
Streaming, 4 cores, 40 streams on one connection (baseline ~240,000 msg/s at
0.013 ms) and on eight (~166,000 at 0.020). Raw tables and the experiment
patch: `results/2026-09-12-local-vt-levers/`.

**The JVM under a 1 GB limit runs Serial GC with a ~5 MB young generation.**
JDK 21 (and 25) is not "server-class" below 2 GB and picks Serial; the young
generation starts tiny and grows slowly, so a streaming arm collects almost
continuously and every collection is a safepoint that stalls the loops and
the carriers. The cluster's 1-CPU arms are the same shape.

| lever | one connection | eight connections | cost |
|---|---|---|---|
| **size the young generation** (`-Xmn256m`; ParallelGC gives the same) | ~303,000, **+25%** | ~221,000, **+33%** | +170–280 MB RSS |
| **batch inbound credits** (`request(n)` instead of `request(1)` per message) | ~303,000 at 0.010 ms, **+27%**, −23% CPU/msg | ~230,000 at 0.013, **+40%**, −35% CPU/msg | none |
| two event loops instead of the default eight | nil | ~189,000, +14% | none |
| a long-lived virtual thread per call | nil | +7% | not worth it |
| G1 / generational ZGC instead of Serial | +15% / +6% | — | +130 / +540 MB RSS |
| JDK 25 runtime | −12% | +30% at 727 MB RSS | not a lever |
| **stacked** (young gen + credits, + two loops on eight) | **~445,000 at 0.008–0.009** | **~414,000 at 0.009**, CPUs at 98% | |

**The GC lever is young-generation size, not the collector.** Serial with
`-Xmn256m`, ParallelGC, and both land on the same ~303,000; G1 and ZGC are
worse than a sized Serial and cost more memory. It moves `:direct` just as
much: on eight connections `:direct` goes from ~278,000 to ~468,000 (Serial +
`-Xmn256m`) or ~487,000 (Parallel) at 0.008 ms on 3.9 cores — which is what
the "unresolved plateau" above was.

**Batched credits are the one lever that lowers CPU per message.** grpc-java's
streaming listener re-requests one credit per delivered message; on an
off-loop executor that is one hop from the handler's thread back to the
event loop per message, and the loop was the single-connection ceiling.
Requesting eight at a time removes it; 8, 32 and 128 give the same number.
It is clj-grpc's `:inbound-credits n` server option (`disableAutoRequest` +
`request(n)` on the streaming-in handlers), and the chart sets 8 on the gRPC
arms from 0.2.23, alongside `jvmOptions: "-Xmn256m"` on every JVM arm.

**Stacked, virtual threads reach 85–90% of tuned `:direct` on the same
cores**, from 60–65% at the defaults, and the single-connection ceiling
(~445,000, where the single driver worker was also at its limit) is now above
`:direct`'s untuned eight-connection one. The executor rule stands — one
connection wants virtual threads, many want `:direct` — but the gap between
them is mostly two defaults, not the executors.

**On the cluster it is worth +23% on unary and +14% on streaming.** Every
JVM figure in this document was measured with the Serial default young
generation, so `-Xmn256m` was injected on the 1-CPU arms and the ladder's
ramps re-run (`results/2026-09-12-younggen/`): gRPC unary ~8,050 rps at
0.120 ms against the median 6,540 at ~0.15 (+23% capacity, −20% CPU per
request); streaming ~16,000 msg/s at 0.054 against 14,000 at ~0.062 (+14%,
−12%, still at the one-connection loop cap); REST HTTP/1.1 ~835 against
795 (+5%). RSS roughly doubles (175 → 355 MB on the gRPC arm). Chart 0.2.23
makes it the default on every JVM arm and sets `:inbound-credits 8` on the
gRPC arms; the ladder table above predates both.

**On chart 0.2.23 itself** (`results/2026-09-12-chart23/`, both defaults
verified on the pod, 1 CPU): `:direct` unary ~8,300 rps at 0.119 ms (+3% on
`-Xmn` alone — the credits touch streaming only) and `:direct` streaming
~16,000 at 0.054, identical to `-Xmn` alone, because under `:direct` the
one-per-message request already ran on the loop and there was no hop to
save. **The credits are a virtual-thread lever**: virtual-thread streaming
reaches a knee of ~13,400 and a plateau of ~13,900 msg/s at 0.069 ms, from
~7,900 on the last virtual-thread baseline (chart 0.2.8) — +76%, and 0.87×
`:direct` on one CPU. The typed interop arm on virtual threads reaches
~16,000 at 0.059 (−14–15% CPU per message at saturation, −4–7% below the
knee, p50 lower at every step): `:direct`'s compiled plateau, on the safe
executor.

**Three more options, measured on the pinned x86 host against a clean
baseline that carries both defaults** (`results/2026-09-12-local-plan-1-3/`):
`:worker-threads` 1 or 2 instead of Netty's 2 × cores is **+10–12%** for an
eight-connection virtual-thread server (four loops +6%, one loop as good as
two) and nil on one connection; `:initial-flow-control-window` is nil on
one connection at 4 and 16 MiB (the loop thread's share is 97% either way,
tails improve) but **+17% on eight connections** at 16 MiB with two loops
(~388,000 against ~332,000, one run — each connection starts with its own
window, and with eight of them BDP has less traffic per connection to grow
it from); the scheduler's parallelism at cores minus loops is nil on one
connection and −11% on eight. With the defaults
shipped, the per-thread read at the single-connection plateau is the
connection's event loop at 95–97%, the carriers at 65% and the VM thread at
1.4%: GC is gone and one connection's loop is the ceiling, and no option in
this section moves it — only the payload work each message costs (the
typed path, and the codec's own read path) or a second connection does.

## Where the CPU goes

**REST's extra ~1.3 ms per request is not JSON** (0.06 ms). It is the
Pedestal/Clojure request pipeline (0.41 ms of persistent maps, Vars and seqs,
plus 0.24 ms of Java collections and locks), Jetty (0.17 ms), and 8× the syscall
time of gRPC — per-connection `writev` and thread-pool hand-offs against one
multiplexed socket on an event loop.

**On the gRPC arms at production shape, roughly two thirds of per-message CPU is
payload handling** — 64 µs per message at ~1 KB against 20 µs at 7 bytes. It is
owned by the codec and the value representation, not the transport and not the
network: between those two tiers, bytes on the wire fall 27× while node softirq
falls 7% carrying 2.4× the messages.

**Both field count and bytes matter, and at production shape bytes and fixed
overhead dominate.** Measured directly with a pair built for it: `realistic` is
1,025 bytes across 30 leaf values, `dense` is 1,030 bytes across 120 — the same
wire size within half a percent, four times the fields. Same arm, same ramp,
same session, every step below the knee:

| offered | realistic | dense | Δ |
|---|---|---|---|
| 500 | 0.513 ms | 0.582 ms | +13.5% |
| 1,000 | 0.388 | 0.471 | +21.4% |
| 1,500 | 0.342 | 0.416 | +21.6% |
| 2,000 | 0.300 | 0.358 | +19.3% |

**Four times the fields costs about +20%, not 4×.** The implied per-field cost is
**0.64–0.71 µs**, so `realistic`'s 30 fields are roughly 19–21 µs of work per
message. **Which budget that is a share of matters, so name it:** against a
*unary request below the knee* (~300 µs at 2,000 rps, 1 CPU) it is 6–8%; against
a *streamed message at the knee* (~64 µs at 2 cores) the same ~21 µs is about a
third. Both are true of different quantities. A reader who takes "the field term
is a minority" as a statement about the codec rather than about a whole unary
request will underweight it by roughly 5×.

clj-protobuf measured the same pair with no transport in it (their bench,
main at 411bf49, 2026-09-12, x86): compiled decode 2.72 µs for the
realistic shape and 9.81 µs for dense — 3.6× for 4× the fields, so **the
codec's own field term is near-linear in field count** at ~80 ns per leaf
on x86. The soak's ~0.64–0.71 µs per leaf is the whole request path on the
CM5 (decode, conversion, encode, copies, at 3–4× the per-instruction
cost); the codec is a minority of it, which is the same split the per-byte
term showed. At ~1 KB their harness also puts protobuf ahead of JSON in
both directions, decode by 2.3–2.8×, where the small shapes had been mixed.

That corrects the advice this document previously gave ("count fields, treat one
large value as nearly free per byte"), which was inferred from comparing 1,025
bytes / 30 leaves against 7 bytes / 1 leaf — a pair that moves both axes at once
and cannot separate them. Taking the earlier tiny-vs-realistic delta of ~44 µs
and subtracting the ~20 µs the field term now accounts for leaves ~24 µs across
~1,018 bytes, so the per-byte term is real at roughly 20–25 ns/byte rather than
negligible.

**And most of that per-byte term is not the codec.** clj-protobuf measured its
own marginal cost of bulk directly — two shapes differing only in the length of
one string, encode+decode through the full Clojure pipeline, no transport —
at **1.15 ns/byte** on x86. Allowing 3–4× for CM5, the codec accounts for perhaps
4–5 ns/byte of the 20–25 measured here. The remaining three quarters is framing,
buffer copies and socket writes: whatever scales with bytes on the path *around*
the codec. A filler string costs the codec about 1 ns/byte and costs the system
about 24. (Their intercept is not usable as a fixed per-message cost — the p2
fixture scans ~40 declared fields however few are set — but the marginal is
robust because that scan cancels between the two rows.)

**The useful form: at ~1 KB, expect a large fixed per-message cost, a real
per-byte term, and a field term that is a minority unless the message is unusually
dense.** A message that is mostly one large value is not cheap, and a message with
four times the fields is not four times dearer.

*(The 2,500-offered step is excluded above: `dense` had begun shedding there —
45.9/s, 7.5 s throttled — which inflates its CPU. Node stayed at 2.2–3.1 of 4
throughout, so none of this is host-limited.)*

**A typed read path for the compiled arm is worth 4–9% per streamed
message where the message is cheapest, and little elsewhere.**
protoc-gen-clojure 0.7.0 reads a compiled message's slots directly by
declaration index (clj-protobuf 0.3.0's `rt/slot`) instead of through the
descriptor per field. Measured as a one-variable pair on the pinned x86
host (`results/2026-09-12-local-typed-slot/`): `:direct` one connection
7.8 → 7.1 µs per message (−6–9%, +8% capacity), virtual threads one
connection 9.6 → 9.0 (−4–6%), eight-connection shapes 0–3%. A constant
~0.5–0.7 µs saved per message, which is the conversion inside the field
term. clj-protobuf's own bench (no transport, same-JVM A/B) puts the
decode-only saving at 1.45 µs on the realistic shape — 3.28 → 1.83 µs, and
−37 to −47% across its six field-dense shapes, nil on the three
collection-dominated ones, with an encode column that changed nothing and
wandered ±20–30% as that harness's noise floor. The two agree once the
denominators are named: a streamed message here is one decode plus one
encode, and 0.7.0 changes only the decode (the write paths are textually
identical between the two fixtures), so the per-message saving is about
half the decode-only saving — 0.72 µs predicted, 0.5–0.7 measured. Nothing
is lost to the transport; the message simply contains a second operation
the change does not touch. It is the compiled arm's counterpart of
`interop=true`'s typed reads without generated Java classes. The
alternative design — parsing straight into the record and giving up the
`Message` contract and unknown-field preservation — was measured against
it three ways in one JVM on clj-protobuf's side: typed −42% / −36% against
compiled on the realistic and dense shapes, the parse-into-record
prototype −35% / −19%, so the path that keeps the contract is the faster
one and the question is closed (the prototype was a naive tag loop, so it
bounds the idea's floor rather than its ceiling; nobody is scheduling the
rebuild that would find out). That second run also reproduces the
field-dense band: −42% / −36% against −44% / −44% in the nine-shape run,
two harnesses, same band.

**Streaming's gain over unary is grpc-java shrinking**, 8.4% of samples (0.023
ms) to 3.8% (0.006 ms): per-RPC setup, headers and trailers amortized over a
stream. What remains is protobuf, syscalls and copies — the message itself.

**JIT and GC are 5–7% everywhere** at steady state.

## Levers, each measured separately

**Three of these are already in the ladder figures above and are not additive to
them.** The chart enables direct linking and the compiled codec by default and
the arms run `:direct`, so a current measurement contains all three. Each figure
says what you lose by turning one off, not what you gain by adding it.

| lever | worth | already in the ladder? |
|---|---|---|
| `-Dclojure.compiler.direct-linking=true` on the arm's JVM | 3–16% CPU per request, 3–17% per streamed message | **yes**, chart default since 0.2.12 |
| clj-protobuf's descriptor-compiled codec | 6–17% CPU; protobuf 26% of samples → 2% | **yes**, chart default |
| `:direct` over the default virtual-thread executor | 15–27% CPU, ~25% stream capacity, p50 roughly half at 1 CPU; on multi-core pods it depends on connections — see "The executor, and cores" | **yes**, arm default |
| protoc-gen-clojure `interop=true` | p50 −9 to −45%; CPU −10–22% per streamed message, nil (0–4%) per unary request — see below | no, a separate arm |
| a sized young generation (`-Xmn256m`; the 1 GB-limit default is Serial with ~5 MB) | +25–33% streaming on virtual threads, +70% on `:direct` with eight connections, at 4 cores (x86) | chart default from 0.2.23 (`jvmOptions`); the ladder above predates it |
| batched inbound credits (clj-grpc `:inbound-credits`) | +27–40% streaming on virtual threads, −23–35% CPU/msg (x86) | chart default 8 from 0.2.23 (`inboundCredits`); the ladder above predates it |
| `:worker-threads` 1–2 (Netty's default is 2 × cores) | +10–12% streaming for a many-connection virtual-thread server; nil on one connection; leave the default for `:direct` (x86) | no — an option since #99 |
| protoc-gen-clojure 0.7.0's typed-slot read path on the compiled arm | −6–9% CPU per streamed message on `:direct` one connection (+8% capacity), −4–6% on virtual threads one connection, 0–3% on eight-connection shapes (x86) | the fixture in this PR; the ladder above predates it |
| `:initial-flow-control-window` 16 MiB | nil on one connection (better tails); +17% on eight connections with two loops, one run (x86) | no — an option since #99 |
| virtual-thread scheduler parallelism at cores − loops | nil on one connection, −11% on eight (x86) | no |

### Where the ladder's numbers come from

Chart 0.2.8 → 0.2.21 made unary ~9% cheaper per request. Bracketed by running
each intermediate chart on the same ramp and node, node CPU sampled to confirm
none was host-limited:

| chart | delivered @6,000 | ms/req @5,000 | what it adds |
|---|---|---|---|
| 0.2.8 | 5,472 | 0.172 | the stack the previous ladder came from |
| 0.2.9 | 5,734 | 0.167 | protobuf-java 4.36.1, clj-protobuf 0.2.2 |
| 0.2.21, linking off | 5,875 | 0.164 | everything else since |
| 0.2.19, linking on | 5,973 | 0.155 | direct linking |
| 0.2.21, linking on | 5,999 | 0.156 | clj-protobuf 0.2.5 |

Direct linking is the largest single contributor at ~4.9% of per-request CPU,
protobuf-java 4.36.1 ~2.9%, everything else ~1.8%. **clj-protobuf 0.2.2 → 0.2.5
is nil** — 0.0 / −0.9 / −1.0 / +0.6 / −2.1% across the ramp.

**Typed interop is cheaper on CPU where the codec is a large share of the
message — streaming — and nil where it is not — unary.** It wins p50 by 9–45%
everywhere. On CPU, paired on one host, both arms on the same executor:

| measurement | mode | executor | interop CPU | interop p50 | pairs |
|---|---|---|---|---|---|
| 1 CPU, chart 0.2.18 | unary | virtual threads | +3–8% | −15 to −45% | 2 |
| 1 CPU, chart 0.2.21 | unary | virtual threads | −3.3% | −10.3% | 1 |
| 1 CPU, chart 0.2.21 | unary | `:direct` | −4.1% | −9.3% | 1 |
| 2 cores, chart 0.2.21 | stream | `:direct` | **−8.6%** | lower | 1 |
| 2 cores, chart 0.2.22 | stream | `:direct` | **−12.1%** | lower | 1 |
| x86 1 core, 2026-09-12 | stream, 8 conn | `:direct` | **−22%** at the knee, +23% capacity | lower | 1 |
| x86 4 cores, 2026-09-12 | stream, 8 conn | `:direct` | −11% at 160k; nil at the (not CPU-bound) plateau | lower | 1 |
| x86 4 cores, 2026-09-12 | unary, 8 conn | `:direct` | **0 to −4%** (nil) | same | 1 |

This document read the cluster's pairs as "the advantage grows with cores",
because the 1-CPU pairs were unary and the 2-core pairs were streaming. The
pinned x86 host, where both modes run at both core counts, separates the two:
at 4 cores unary is nil (0–4%, the replicate floor) while streaming at 1 core
is −22% per message and +23% capacity. The variable is the mode. A unary RPC
is mostly grpc-java's per-call machinery, and the typed conversions are a
small share of it; a streamed message is mostly codec and copies, and they
are a large share. (`interop=true` on protoc-gen-clojure ≥ 0.6.0 types both
directions — the arm's `proto->X` reads through the generated class's getters
when the class is present — so the figure is the whole typed path's value,
not a write-side one.) The 2-core cluster pairs were also host-limited (node
above 4.0 of 4), which is a second reason not to read them as a core-count
effect.

The "+3–8% dearer" this document carried for two days was measured on chart
0.2.18 and does not reproduce on 0.2.21/0.2.22 under either executor. This
document offered three explanations for it — core count, clj-protobuf's removed
monitors, the executor — and none survived measurement. An interim version then
called the whole CPU effect noise; with the replicate floor now known, that was
also too strong. The 0.2.18 figure is most likely a stack difference that has
since closed, and is not worth further chasing.

clj-protobuf's two process-wide `Collections.synchronizedMap`s, removed in
0.2.5, were once the leading explanation for the supposed flip. They are not:
measured at 1 CPU with the host demonstrably idle, 0.2.2 against 0.2.5 is nil.

**Not levers:** protobuf-java 4.36.1 vs 4.35.1, Netty leak detection, pinning the
VT scheduler to one carrier — each ≤ 4% or nil.

**Direct linking reaches code the build-time attribute cannot.** clj-protobuf
ships to Clojars as source *by design*, so its codec is compiled by Clojure at
load time and an ahead-of-time caller may not link into it at all. rules_clj's
`direct_linking` attribute and the runtime property cover disjoint code; neither
covers both. The runtime property is process-wide and **stops `with-redefs`
working on linked call sites** — a deployment decision, not a build flag, and a
good way to be surprised in staging.

### Two more things worth knowing before sizing

**The codec has a kill switch.** `-Dclj-protobuf.codec=dynamic` swaps the
compiled codec for `DynamicMessage` at load — one env change, no rebuild. It is
how the codec was A/B'd on a live chart here, and it is the fallback if the codec
is ever suspected.

**Handles are arm-specific.** `proto->X` given a message from another arm throws
rather than converting. It only matters if a service mixes arms, and it is silent
until it is not.

## Measuring on this cluster

Method that carries forward, each of which cost a wrong conclusion to learn:

**Read the node, not just the pod.** Kernel softirq, VXLAN encap and the CNI
path are charged to the node, not the pod's cgroup. A 2-core arm on these
4-core nodes has **~2.2 cores of real headroom**, not 2, because resident
workloads take ~1.8 — and at saturation the node ran 4.11 of 4 cores while the
pod's cgroup showed 1.49 of 2 with no throttling. A pod that cannot be
*scheduled* looks exactly like a pod with spare capacity. Prometheus here has no
node-exporter; `soak/node-cpu.sh` reads Talos's native per-CPU counters instead
and needs nothing deployed.

**Check the driver.** A load generator out of CPU produces a plateau
indistinguishable from the server's. `soak/client-cpu.sh` samples the Job's own
cgroup and reports **throttling**, not usage — the spin idle strategy busy-waits,
so usage alone says nothing.

**Take plateaus from the upper steps of a ramp — and run the ramp past the
knee.** Two distinct errors, both committed here:

*The first step after warmup under-reads by 10–13%.* The same arm at the same
connection count gave 19,598 msg/s reached through a ramp and 17,731 as a ramp's
first step; unary gave 5,999 and 5,240 at 6,000 offered the same way. Five
minutes at 200 rps does not warm a JVM for 20,000.

*A ramp that stops at the knee has not found the ceiling.* The previous ladder's
gRPC ramps ended at 1.2% and 0.2% shedding and were read as plateaus; the same
charts with longer ramps deliver ~15% more. A knee counter that has just begun
moving means keep going, not stop.

**Assert, do not compute — and read the pod, not the values.** Connection counts
come from `upstream_cx_total`; image identity and JVM flags from the running
pods. `helm --set-string directLinking.enabled=false` sets the *string*
`"false"`, which Helm's `and` treats as true: the flag stays on and a lever run
silently compares a setting against itself. `LADDER_EXTRA_SET` goes through
`--set-string` and so cannot express a boolean false; an empty value works, and
only the pod proves it.

**One arm per JVM.** Several arms in one process turns the encode call site
megamorphic, which is worth about as much as the effects being measured.

**Before trusting a measurement, ask what the instrument is structurally
incapable of charging to the thing being measured — and whether the answer in
hand is one it would produce either way.** A CPU profile cannot charge time to a
parked thread. A pod cgroup cannot charge softirq to the pod. Each silence in
this campaign was read as absence at least once.

## Raw data

Per-step tables, Job logs, banked flamegraphs and node/driver samples:

- [`results/2026-09-switch-ladder-raw.md`](results/2026-09-switch-ladder-raw.md)
  — the switch-ladder campaign: transport, protocol and interaction-model
  phases, the re-baseline, executor and codec comparisons, multi-core and
  connection work, and the node-saturation finding.
- [`results/2026-08-30-streaming-raw.md`](results/2026-08-30-streaming-raw.md),
  [`results/2026-08-29-capacity-raw.md`](results/2026-08-29-capacity-raw.md),
  [`results/2026-08-29-executor-grid.md`](results/2026-08-29-executor-grid.md)
  — August work on a different instrument (k6 and a custom Clojure driver).
  **Its cross-protocol ratios were withdrawn**: the driver was under-measuring
  unary by 2–4×, which is why the ladder above was re-measured on one
  instrument. The within-arm executor and latency observations stand.

## A known gap in the evidence

Every shape in clj-protobuf's benchmark is 9–443 bytes on the wire. The ceilings
are 443 bytes, 101 leaf values, and ~50 nested message constructions, and no
single shape combines a production-sized body with production field density.
Figures cited from that suite therefore describe the small end of the curve —
including the deep-shape encode pair (412 vs 650 ns) quoted in the raw results,
which is 27 bytes and 5 leaf scalars.

The gap was symmetric, which is why it went unnoticed on both sides: **this
campaign's payload had production size without production field density, and
clj-protobuf's corpus has neither.** Half of it is now closed — the `dense`
tier is 1,030 bytes across 120 leaf values, and running it against `realistic`
at matched rates is what produced the field-vs-byte split above. The other half
— a ~1 KB shape in clj-protobuf's own benchmark — is their call, and the dense
result changes what it would be for: their harness has no transport, so it can
measure the codec's ~1 ns/byte and the field-density term accurately and is
structurally incapable of measuring the ~24 ns/byte that dominates here. The
honest case for that shape is field-density realism, not the per-byte term,
which belongs to this harness.
