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
Nighthawk). Chart 0.2.21, 2026-09-10, with node CPU sampled at every step to
confirm the host was never the limit.

| rung | switch | capacity per 1-CPU pod | vs REST | migration cost |
|---|---|---|---|---|
| 0 | REST HTTP/1.1 | ~790 rps | — | — |
| 1 | → h2c | ~800 rps | 1× | a server config flag; clients must speak h2c |
| 2 | → gRPC unary | ~6,700 rps | **8.5×** | new clients, protobuf schema, serialization; API shape unchanged |
| 3 | → gRPC stream | ~14,400 msg/s | **18×** | API contract: persistent connections, ordering, backpressure |

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

**Read every figure as "at least".** Two runs of the identical chart an hour
apart put the same arm at 5,999 and 5,240 rps at 6,000 offered — one climbed to
6,000 through a ramp, the other started there. Run-to-run and ramp-shape spread
on this harness is ~15%, wider than many of the differences this repo has drawn
conclusions from.

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

**That cost scales with structure, not size.** clj-protobuf's corpus shows cost
tracking field count rather than bytes: two shapes at the *same* 443 bytes carry
51 and 101 leaf values and cost in proportion to the leaves, not the bytes. Field
**kind** matters second — shapes that construct nested messages (repeated message
fields, and map fields, whose entries *are* two-field messages) run about 165 ns
per leaf against 109–120 for flat scalar shapes, so roughly 1.4–1.5×, not a
multiple.

The working model is `(field count × per-field cost, weighted by kind) + (bytes ×
a small per-byte term)`, and at production shape the first dominates: per-field
cost is 100–165 ns while a large single value is order 1 ns/byte.

So: **count fields, weight nested messages and map entries somewhat above
scalars, and treat one large value as nearly free per byte.** Note also that the
44 µs delta above is not purely a field-count result — the realistic tier's bulk
is one 800-byte filler string, so it carries a real per-byte term too.

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
| protoc-gen-clojure `interop=true` | executor dependent — see below | no, a separate arm |

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

**Typed interop's CPU cost tracks the executor, not the core count.** It wins
p50 by 6–45% everywhere measured. On CPU, the three measurements line up on
executor and not on cores:

| measurement | executor | interop CPU vs compiled |
|---|---|---|
| 1 CPU, chart 0.2.18 | virtual threads | **+3–8%** |
| 2 cores, charts 0.2.19 / 0.2.21 | `:direct` | −10 to −14% |
| 1 CPU, chart 0.2.21 | `:direct` | −4.1% |

This document previously called that a core-count dependent sign flip. It is
not: core count was confounded with executor choice. Every run showing interop
dearer was on virtual threads; every run showing it cheaper was on `:direct`.
Under `:direct`, interop is level-to-cheaper on CPU at both core counts and wins
p50 at every step.

Two caveats. The `:direct` runs are on later charts than the VT one, so the
executor is not perfectly isolated — a VT pair on the current chart would settle
it and has not been run. And −4.1% sits inside this harness's ~15% spread; the
p50 advantage (−2 to −20%, negative at all five steps) is the more consistent
half.

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

The gap is symmetric, which is why it went unnoticed on both sides: **this
campaign's payload has production size without production field density, and
clj-protobuf's corpus has neither.** Closing it needs a ~1 KB nested shape, and
ideally a pair holding bytes constant while varying field count so the two axes
separate.
