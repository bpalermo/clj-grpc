# clj-grpc

gRPC for Clojure on **non-shaded Netty**, over
[clj-protobuf](https://github.com/bpalermo/clj-protobuf): services and clients
built dynamically from
[protoc-gen-clojure](https://github.com/bpalermo/protoc-gen-clojure) generated
code — plain functions in, protobuf Messages on the wire.

```clojure
;; deps.edn
com.github.bpalermo/clj-grpc {:mvn/version "0.1.3"}
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

- **`:executor :direct`** runs handlers on the Netty event loop: **−29% unary
  latency** (265 → 187 µs loopback) for provably non-blocking handlers — and
  the inverse under load, where the default virtual-thread executor wins by
  ~9% (23,060 vs 21,133 calls/s at 32-way concurrency; `bazel run //bench:run
  -- load` reproduces both). A blocking handler on a direct executor stalls
  every connection on that loop. Default stays virtual threads.
- **Streaming beats tuning by two orders of magnitude.** Every unary call
  costs ~190–275 µs of machinery; serializing an entire 20-row message costs
  ~7 µs. If a workload makes N small calls where one stream would do, no
  executor choice compares to fixing that.

## Cold start

Time-to-first-RPC for a cold server process — the number Knative
scale-from-zero pays. Measured with `//bench:coldstart` (spawn to first
successful call, warm prober, fresh channel per probe, median of 5):

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

## Building

Bazel (with [rules_clj](https://github.com/bpalermo/rules_clj)):
`bazel test //...` — the e2e suite runs every streaming shape over real Netty,
TCP and UDS both. `clojure -X:test` runs the non-Bazel subset; the Clojars
artifact is `clojure -T:build jar`.

## License

Apache-2.0
