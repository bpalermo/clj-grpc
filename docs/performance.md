# Performance

Every number here was measured, and says where and when. None of it is
asserted from first principles, and where a measurement has been withdrawn or
is doubted, that is recorded next to it rather than quietly dropped.

Three documents, by instrument:

- **this one** — loopback benchmarks (`//bench`), the tuning levers a consumer
  can reach, and the native-image trade.
- [`soak-results.md`](soak-results.md) — on-cluster campaigns: multi-hour
  soaks, capacity ramps past the knee, 1-CPU and multi-core pods.
- [`results/`](results/) — raw per-step tables, one file per campaign.

A loopback number and a cluster number disagree more often than not, and when
they do the cluster is the one that describes production: real network, real
body sizes, a container's CPU budget. The loopback figures are for comparing
two arms of the same shape on one machine.

## Choosing an executor

Handlers run on virtual threads by default. `:executor :direct` runs them on
the Netty event loop instead, and what that buys depends entirely on how many
cores the process has and how many connections its clients open.

**On loopback, with cores to spare**, `:direct` is −29% unary latency (265 →
187 µs) and about 9% *less* throughput than the virtual-thread default at
32-way concurrency (21,133 vs 23,060 calls/s). `bazel run //bench:run -- load`
reproduces both.

**On a 1-CPU pod**, where one event loop is the whole machine, that inversion
disappears: the on-cluster ladder puts `:direct` ahead on every axis — 15–27%
less CPU per unary request, ~25% more streaming capacity, p50 roughly half at
every matched rate.

**On a multi-core pod the answer is the client's connection count.** A
connection binds to one event loop, so one connection under `:direct` is one
event loop and one core at any core count, while virtual threads spread that
single connection across the cores available — 2.5× `:direct`'s
single-connection ceiling at 4 cores. Given eight connections `:direct` scales
too, delivering ~1.7× the virtual-thread throughput at half the CPU per
message; and every connection added to a virtual-thread server *costs* it
throughput.

So: `:direct` for 1-CPU pods and many-connection clients, virtual threads for
a single-connection client on a multi-core pod.

**The case against `:direct` is unchanged and absolute**: a handler that blocks
on a direct executor stalls every connection sharing that event loop. The
default stays virtual threads — the only safe setting for handlers that may
block, and the one grpc-java intends to keep optimising.

## Server options worth setting

- **`:worker-threads` 1–2** on a virtual-thread server with many connections,
  rather than Netty's default 2 × cores: +10–12% streamed messages per second
  on 4 cores. The loops only do I/O there and otherwise compete with the
  carriers. Leave the default for `:direct`, where the loops run the handlers.
- **`-Xmn256m`**: under a 1 GB container limit the JVM selects Serial GC with a
  ~5 MB young generation. Sizing it is worth +25–70% streaming on 4 cores.
- **`:inbound-credits n`**: grpc-java re-requests one credit per streamed
  message, each a hop from the handler's thread back to the event loop.
  Batching them is +27–40% streamed messages per second on virtual threads at
  −23–35% CPU per message. Above a handful the batch size stops mattering — 8,
  32 and 128 measure the same.
- **`:initial-flow-control-window`** where the HTTP/2 window is the limit
  rather than the CPU.

The soak chart sets the young generation and the credits by default.

Outbound, `send!` returns whether the transport wants more and a `:bidi`
handler may declare `:on-ready`. That is correctness rather than throughput —
without it a handler producing on its own schedule grows grpc-java's per-call
buffer without bound — and
[`examples/src/example/echo/async.clj`](../examples/src/example/echo/async.clj)
is the worked case over core.async.

## Generate streaming services with `interop=true`

protoc-gen-clojure's typed fast paths (both directions since 0.6.0) measured
**+23% capacity and −22% CPU per streamed message** at one core on the soak.

On unary the CPU and capacity effect is nil — 0–4%, because grpc-java's
per-call machinery dominates — but p50 still improves 9–45%. Streaming for
capacity, unary for latency.

It is one attribute plus one dependency:

```python
clojure_proto_library(
    name = "greeter_clj",
    proto = "//proto:greeter_proto",
    options = {"interop": "true"},
    outs = ["acme/greeter/greeter.clj"],
)
clj_library(
    name = "greeter",
    srcs = [":greeter_clj"],
    deps = ["//proto:greeter_java_proto", ...],  # the generated ns loads protoc's classes
)
```

The generated namespace requires protoc's Java classes on the classpath at
load, so a native image would need their reflection config; the
embedded-descriptor arm stays the default for that reason.

## Streaming beats tuning by two orders of magnitude

Every unary call costs ~190–275 µs of machinery. Serializing an entire 20-row
message costs ~7 µs. If a workload makes N small calls where one stream would
do, no executor choice compares to fixing that.

## Against an ordinary REST stack

`bazel run //bench:run` measures full round trips on loopback with persistent
connections — identical echo semantics, this library against the ordinary
Clojure REST stack (Pedestal 0.8.1 on Jetty, jsonista both sides, JDK
HttpClient). Mean latency, quick-mode criterium, JDK 21, Linux x86_64:

| payload | gRPC (clj-grpc) | REST (Pedestal+JSON) |
|---|---|---|
| small (~10 B) | 252 µs | 826 µs |
| medium (1 KB) | 280 µs | 902 µs |
| large (64 KB) | 1.57 ms | 4.37 ms |

~3× at every size. The smoke test keeps both arms serving and agreeing on
every `bazel test //...`.

**Provenance, and one claim to distrust.** This table was measured 2026-08-23
on clj-protobuf 0.1.6 — before the descriptor-compiled codec (0.2.0) this
library now depends on, which is worth 6–17% CPU per request on the cluster. A
single rerun on 0.2.2 put small and medium within a few percent of the rows
above, but the 64 KB ratio near 1.8× rather than 2.8× — so **"the gap holds to
bytes-dominated payloads" is the sentence to doubt.** That rerun was taken on a
machine busy with other builds and moved both arms in implausible directions,
so it is not enough to republish. The table stands as measured until someone
reruns `bazel run //bench:run` on a quiet host.

On the cluster, at 1 KB bodies over a real network, the ratios are far larger
than 3× — see [`soak-results.md`](soak-results.md).

## Cold start

Time-to-first-RPC for a cold server process — the number Knative
scale-from-zero pays. Measured with `//bench:coldstart` (spawn to first
successful call, warm prober, fresh channel per probe, median of 5), 2026-08-29
on clj-protobuf 0.1.6:

| arm | median | range |
|---|---|---|
| plain deploy jar | 1750 ms | 1725–1848 ms |
| AppCDS (archive trained through a served RPC) | 1707 ms | 1678–1798 ms |
| **GraalVM native-image** | **79 ms** | 71–420 ms |

That is **22× over the JVM arm**, and the native binary serves over Unix domain
sockets through the embedded epoll JNI transport like any other. The contract
that makes a native image possible at all is in the
[README](../README.md#native-image).

**AppCDS buys about 2%, not the 57% previously published.** The old prober
reused one channel, so gRPC's reconnect backoff quantized every reading; a
fresh channel per probe removes up to a full backoff period of inflation from
the JVM arms. This workload's startup is dominated by executing Clojure's
class initializers, which CDS cannot skip, not by parsing class files, which
it can. The earlier −57% claim was the probe grid amplifying a small
difference, and is withdrawn.

## Steady state, and the native trade

`//bench:steady` spawns the same two servers and measures the ten-thousandth
RPC instead of the first — 20k-call warmup, then sequential unary latency and
32-way virtual-thread throughput, same warm JVM client for both arms. Also
2026-08-29 on clj-protobuf 0.1.6:

| arm | unary p50 | p90 | p99 | 32-way throughput |
|---|---|---|---|---|
| JVM (warmed) | 247 µs | 302 µs | 431 µs | ~29,000 calls/s |
| native image | 315 µs | 398 µs | 527 µs | ~18,000 calls/s |

Once the JIT is warm the JVM serves ~25% lower latency and ~55% more
throughput; the native image runs whatever the image builder froze, on Serial
GC. The native arm is also ~2.1× smaller — 182 MB RSS against the JVM's
381 MB after the same load.

**What moves the native number and what does not**, measured: `-O3` and
`-march=native` change nothing, because the hot path is I/O and dispatch
rather than compute; sizing the Serial GC at run time (`-Xmx1g -Xmn512m` as
arguments to the binary) buys ~13% throughput for free. The levers that could
close the rest of the gap — PGO and G1 — need Oracle GraalVM, and images built
with Oracle GraalVM 21.0.12 or 25.0.4 currently fail every RPC (`CANCELLED:
Failed to read message`, isolated to the toolchain version rather than to
those features; CE 21.0.2 works), so they stay unmeasured until that is
diagnosed.

**The throughput gap is narrower than it looks, because the JVM buys its peak
with cores.** Same 64k-call load, server CPU metered from `/proc`
(utime+stime, all threads), servers pinned with `taskset` to container-shaped
budgets:

| budget | native | JVM (warmed) |
|---|---|---|
| 1 core | 17.0–17.4k calls/s, **183 MB** peak | 16.2–17.5k calls/s, 335 MB peak |
| 2 cores | 23.2k calls/s, 58 µs CPU/call, **183 MB** | 23.1k calls/s, 69 µs CPU/call, 434 MB |
| unconstrained (20 cores) | 15–22k calls/s at ~2 cores, 140 µs CPU/call, **181 MB** | 23–29k calls/s at ~4 cores, 169 µs CPU/call, 644 MB |

Per request the native image consistently spends *less* CPU; the JIT's
throughput lead exists only where spare cores exist to burn.

**And the cluster reverses it.** All of the above is an 8-byte echo over a Unix
socket on x86. On the cluster at 1 KB bodies over TCP, the same pod shape has
the native arm delivering 0.3× the JVM's requests at 3–4× the CPU each, in
about half the memory — see the conclusion in
[`soak-results.md`](soak-results.md).

So: **for a service carrying traffic at production body sizes, the JVM. For
scale-from-zero and idle density, native.** Both sets of numbers are honest and
neither invalidates the other; they describe different workloads.

## On the cluster

[`soak-results.md`](soak-results.md) is the campaign record. The headline, on
1 KB bodies, medians on the shipped chart: REST HTTP/1.1 ~836 rps → h2c ~850 →
**gRPC unary ~8,374 (10×) → bidi stream ~17,000 msg/s (20×)**.

Two earlier headlines were *withdrawn* by that campaign rather than refined:
the 7.5×/16× streaming ratios were the old k6 driver under-measuring unary, and
"the executor trade inverts with load" was a loopback artifact.

Those are **per-core** figures, and a gRPC arm reaches them only if the client
opens enough connections: one multiplexed connection to a multi-core pod uses
one core of it — measured pinned at 0.90 cores while the node had a full core
spare. Size clients by connection count as well as by pod CPU.

The harness lives in [`soak/`](../soak/).
