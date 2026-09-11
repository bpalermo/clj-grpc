# clj-grpc

[![Clojars Project](https://img.shields.io/clojars/v/com.github.bpalermo/clj-grpc.svg)](https://clojars.org/com.github.bpalermo/clj-grpc)

gRPC for Clojure on **non-shaded Netty**, over
[clj-protobuf](https://github.com/bpalermo/clj-protobuf): services and clients
built dynamically from
[protoc-gen-clojure](https://github.com/bpalermo/protoc-gen-clojure) generated
code — plain functions in, protobuf Messages on the wire.

```clojure
;; deps.edn
com.github.bpalermo/clj-grpc {:mvn/version "0.1.9"}
```

## Serve

```clojure
(require '[clj-grpc.server :as server]
         '[acme.greeter.greeter :as g])      ; generated

(-> (server/server
     {:services [{:service g/Greeter
                  :handlers {:say-hello
                             (fn [req]
                               (-> {:message (str "Hello " (:name (g/proto->HelloRequest req)))}
                                   g/HelloReply->proto))}}]
      :port 8080})
    server/start)
```

Handler shapes per method type — unary `(fn [req] resp)`, server-streaming
`(fn [req send!])`, client-streaming `(fn [respond!]) -> {:on-next ... :on-complete ...}`,
bidi `(fn [send! close!]) -> {:on-next ...}`. Requests and responses are
protobuf Messages; the generated `proto->X`/`X->proto` fns are the edges.

## Call

```clojure
(require '[clj-grpc.client :as client])

(def ch (client/channel "localhost:8080" {:plaintext true}))
(def greeter (client/client ch g/greeter-methods {:deadline-ms 5000}))

(-> ((:say-hello greeter) (g/HelloRequest->proto {:name "world"}))
    g/proto->HelloReply
    :message)
```

## Examples

[`examples/`](examples/) is the full path, runnable: a `.proto` with one
method per streaming shape, its checked-in generated namespace, a server main
with every handler shape, and a client main with every call shape —
`bazel run //examples:server`, then `bazel run //examples:client`. An e2e test
keeps it honest on every `bazel test //...`, and `//examples:echo_native`
builds the same server as a GraalVM native image — CI round-trips the client
against that binary too.

## Unix domain sockets

Both directions, epoll only (validated eagerly):

```clojure
(server/server {:services [...] :address {:unix "/run/app/grpc.sock"}})
(client/channel "unix:///run/app/grpc.sock" {})
```

## Knative

`clj-grpc.knative` holds the presets: server on `$PORT` speaking h2c with
health + reflection on (name the container port `h2c` in the Service spec),
client with wait-for-ready and keepalives for the activator-in-path,
scale-from-zero posture. See the namespace docstring for the deployment notes.

## Non-shaded Netty, deliberately

`grpc-netty-shaded` makes the native transports unreachable; epoll is how UDS
and the fast path work, so this library pins **grpc 1.83.1 ↔ Netty
4.2.16.Final** per grpc-java's SECURITY.md pairing table, every netty artifact
pinned top-level in `deps.edn`. The `netty_alignment_test` fails CI if any
loaded netty jar drifts — it caught grpc's own pom pulling two 4.2.15 jars on
its first run. When bumping grpc: consult the pairing table, bump the whole
pin block, run the test.

TLS: h2c needs none. For TLS, JDK SSL works out of the box via `:tls`;
`netty-tcnative-boringssl-static 2.0.81.Final` is the optional OpenSSL add-on.

## Performance posture

Two measured levers, honest about their trade:

- **`:executor :direct`** runs handlers on the Netty event loop, and what it
  buys depends on how many cores the process has. On loopback, with cores to
  spare: **−29% unary latency** (265 → 187 µs) and ~9% *less* throughput than
  the virtual-thread default at 32-way concurrency (21,133 vs 23,060 calls/s;
  `bazel run //bench:run -- load` reproduces both). On a 1-CPU pod, where one
  event loop is the whole machine, that inversion disappears: the on-cluster
  ladder measures `:direct` ahead on every axis — 15–27% less CPU per unary
  request, ~25% more streaming capacity, p50 roughly half at every matched
  rate ([docs/soak-results.md](docs/soak-results.md)). So the case for
  `:direct` is tail latency and CPU on a small pod, not throughput in
  general, and the case against it is unchanged and absolute: a handler that
  blocks on a direct executor stalls every connection on that loop. Default
  stays virtual threads — the only safe setting for handlers that may block,
  and the one grpc-java intends to keep optimising.
- **Streaming beats tuning by two orders of magnitude.** Every unary call
  costs ~190–275 µs of machinery; serializing an entire 20-row message costs
  ~7 µs. If a workload makes N small calls where one stream would do, no
  executor choice compares to fixing that.

## Cold start

Time-to-first-RPC for a cold server process — the number Knative
scale-from-zero pays. Measured with `//bench:coldstart` (spawn to first
successful call, warm prober, fresh channel per probe, median of 5) — measured
2026-08-29 on clj-protobuf 0.1.6, before the compiled codec:

| arm | median | range |
|---|---|---|
| plain deploy jar | 1750 ms | 1725–1848 ms |
| AppCDS (archive trained through a served RPC) | 1707 ms | 1678–1798 ms |
| **GraalVM native-image** | **79 ms** | 71–420 ms |

Two corrections over the previously published table. First, the old prober
reused one channel, so gRPC's reconnect backoff quantized every reading; a
fresh channel per probe removes up to a full backoff period of inflation from
the JVM arms — and reveals that AppCDS, honestly measured, buys about 2%
here: this workload's startup is dominated by executing Clojure's class
initializers, which CDS cannot skip, not by parsing class files, which it
can. The earlier −57% CDS claim was the probe grid amplifying a small
difference and is withdrawn.

Second, the native-image arm now **works** — 22× over the JVM, and it serves
over Unix domain sockets through the embedded epoll JNI transport. Every
Netty-touching construction lives in one leaf namespace
(`clj-grpc.impl.netty`) that the API namespaces load via `requiring-resolve`
at first construction, so under `--initialize-at-build-time` nothing
Netty-marked initializes during image build; the jar ships the
`META-INF/native-image` config (run-time-init for the leaf, `io.grpc.netty`,
and `io.netty.handler.ssl`, plus the reflection entries the runtime require
needs), which `native-image` discovers automatically. Build the sample with
`bazel build //bench:coldstart_native`. Two consumer caveats: every namespace
in the image must be AOT-compiled (a native image has no Clojure compiler),
and generated code should run the embedded-descriptor arm — the class-hinted
arm leans on protobuf-java reflection that a native image needs extra
registration for.

The other side of that trade is steady state. `//bench:steady` spawns the
same two servers and measures the ten-thousandth RPC instead of the first —
20k-call warmup, then sequential unary latency and 32-way virtual-thread
throughput, same warm JVM client for both arms — also 2026-08-29 on
clj-protobuf 0.1.6:

| arm | unary p50 | p90 | p99 | 32-way throughput |
|---|---|---|---|---|
| JVM (warmed) | 247 µs | 302 µs | 431 µs | ~29,000 calls/s |
| native image | 315 µs | 398 µs | 527 µs | ~18,000 calls/s |

Once the JIT is warm the JVM serves ~25% lower latency and ~55% more
throughput; the native image runs whatever the image builder froze, on Serial
GC. So the choice is the workload's: scale-from-zero and short-lived
processes want the 79 ms start; hot, always-on services want the JIT. Both
numbers are honest and neither invalidates the other. The native arm is also
~2.1× smaller — 182 MB RSS against the JVM's 381 MB after the same load.

What moves the native number and what does not, measured: `-O3` and
`-march=native` change nothing (the hot path is I/O and dispatch, not
compute); sizing the Serial GC at run time — `-Xmx1g -Xmn512m` as arguments
to the binary — buys ~13% throughput for free. The levers that could close
the rest of the gap, PGO and the G1 collector, need Oracle GraalVM — and
images built with Oracle GraalVM 21.0.12 or 25.0.4 currently fail every RPC
(`CANCELLED: Failed to read message`, isolated to the toolchain version, not
to those features; CE 21.0.2 works), so they stay unmeasured until that is
diagnosed.

The throughput gap is also narrower than it looks, because the JVM buys its
peak with cores. Same 64k-call load, server CPU metered from `/proc`
(utime+stime, all threads), servers pinned with `taskset` to
container-shaped budgets:

| budget | native | JVM (warmed) |
|---|---|---|
| 1 core | 17.0–17.4k calls/s, **183 MB** peak | 16.2–17.5k calls/s, 335 MB peak |
| 2 cores | 23.2k calls/s, 58 µs CPU/call, **183 MB** | 23.1k calls/s, 69 µs CPU/call, 434 MB |
| unconstrained (20 cores) | 15–22k calls/s at ~2 cores, 140 µs CPU/call, **181 MB** | 23–29k calls/s at ~4 cores, 169 µs CPU/call, 644 MB |

Per request, the native image consistently spends *less* CPU; the JIT's
throughput lead exists only where spare cores exist to burn. At the 1–2-CPU
shape a Knative pod actually gets, throughput is a wash and the native image
does it in roughly half the memory — so for pods-per-node density, native
wins on every axis that matters, not just cold start. The JVM's case is the
dedicated always-on service with cores to spare and latency to shave.

## Against REST

`bazel run //bench:run` measures full round trips on loopback with persistent
connections — identical echo semantics, this library versus the ordinary
Clojure REST stack (Pedestal 0.8.1 on Jetty, jsonista both sides, JDK
HttpClient). Mean latency, quick-mode criterium, JDK 21, Linux x86_64:

| payload | gRPC (clj-grpc) | REST (Pedestal+JSON) |
|---|---|---|
| small (~10 B) | 252 µs | 826 µs |
| medium (1 KB) | 280 µs | 902 µs |
| large (64 KB) | 1.57 ms | 4.37 ms |

~3× at every size, and the gap holds from framing-dominated to
bytes-dominated payloads. The smoke test keeps both arms serving and agreeing
on every `bazel test //...`.

**Provenance, and one claim to distrust.** This table was measured 2026-08-23
on clj-protobuf 0.1.6 — before the descriptor-compiled codec (0.2.0) that this
library now depends on, which is worth 6–17% CPU per request on the cluster. A
single rerun on 0.2.2 put small and medium within a few percent of the rows
above but the 64 KB ratio near 1.8× rather than 2.8×, so **"the gap holds …
to bytes-dominated payloads" is the sentence to doubt**. That rerun was taken
on a machine busy with other builds and moved both arms in implausible
directions, so it is not enough to republish: the table stands as measured
until someone reruns `bazel run //bench:run` on a quiet host.

## On-cluster campaigns

Everything above is loopback. [`docs/soak-results.md`](docs/soak-results.md)
carries the on-cluster campaign results — multi-hour soaks, capacity ramps past
the knee, and streaming throughput on identical 1-CPU pods. The ladder on 1 KB
bodies, median of three runs: REST HTTP/1.1 ~795 rps → h2c ~800 → **gRPC unary
~6,540 (8.2×) → bidi stream ~14,000 msg/s (17.6×)**. Two earlier headlines here were
withdrawn by that campaign rather than merely refined: the 7.5×/16× streaming
ratios were the old k6 driver under-measuring unary, and "the executor trade
inverts with load" was a loopback artifact — on a 1-CPU pod `:direct` leads on
CPU per request, capacity and p50 alike.

Those are **per-core** figures, and a gRPC arm reaches them only if the client
opens enough connections: a connection binds to one event loop, so one
multiplexed connection to a multi-core pod uses one core of it — measured pinned
at 0.90 cores while the node had a full core spare. Size clients by connection
count as well as pod CPU.

The harness lives in [`soak/`](soak/), raw per-step tables in
[`docs/results/`](docs/results/).

## Building

Bazel (with [rules_clj](https://github.com/bpalermo/rules_clj)):
`bazel test //...` — the e2e suite runs every streaming shape over real Netty,
TCP and UDS both. `clojure -X:test` runs the non-Bazel subset; the Clojars
artifact is `bazel build //src:clojars`, and `bazel run //src:clojars.publish --
--dry-run` prints every upload it would make.

## License

Apache-2.0
