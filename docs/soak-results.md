# Soak & campaign results

What on-cluster measurement has established about running Clojure gRPC and
REST services, stated as conclusions. The reasoning that produced them, the
corrections along the way, and the runs that were superseded are in git
history; this file is what is currently true.

Measurements are from talos-main: Raspberry Pi CM5 on a DeskPi Super6C, 4 cores
and 8 GB per node, flannel VXLAN, one arm per worker. Harness and procedure in
[`../soak/README.md`](../soak/README.md); raw per-step tables under
[`results/`](results/).

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
| RSS at the knee | 40–106 MB | ~300 MB | **0.3×** memory |

Both native runs are pinned at 0.96–1.00 cores with the node at 2.2–3.1 of 4, so
this is the arm's own limit and not the host's. Under overload it degrades the
way h2c does — p50 rises to 1.7 s (unary) and 2.5 s (stream) rather than
shedding at the client — and `/metrics` stops answering at the top step.

**Native buys memory and startup, and pays for it in throughput.** Per request
it is roughly 3–4× dearer than the JIT-compiled JVM at steady state, which is
consistent with what a closed-world AOT compile of a dynamic language gives up.
The August figure of ~1,550 unary on the k6 driver was the driver, not the arm.

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
| `:direct` over the default virtual-thread executor | 15–27% CPU, ~25% stream capacity, p50 roughly half | **yes**, arm default |
| protoc-gen-clojure `interop=true` | p50 −9 to −45%; CPU −3–4% at 1 CPU, −9–12% at 2 cores — see below | no, a separate arm |

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

**Typed interop is cheaper on CPU everywhere measured on the current stack, and
the advantage grows with cores.** It wins p50 by 9–45% everywhere. On CPU,
paired on one node, both arms on the same executor:

| measurement | executor | interop CPU | interop p50 | pairs |
|---|---|---|---|---|
| 1 CPU, chart 0.2.18 | virtual threads | +3–8% | −15 to −45% | 2 |
| 1 CPU, chart 0.2.21 | virtual threads | −3.3% | −10.3% | 1 |
| 1 CPU, chart 0.2.21 | `:direct` | −4.1% | −9.3% | 1 |
| 2 cores, chart 0.2.21 | `:direct` | **−8.6%** | lower | 1 |
| 2 cores, chart 0.2.22 | `:direct` | **−12.1%** | lower | 1 |

At 1 CPU the effect is ~3–4%, at the edge of the ≤4% replicate noise floor —
real in sign (negative at every step of every pair on the current stack) but not
reliably in magnitude. At 2 cores it is 9–12%, well outside noise, stable across
two pairs, every step negative. Both 2-core pairs were host-limited (node above
4.0 of 4 at the top steps), so they compare the arms at a shared ceiling rather
than measuring either arm's own.

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
