# clj-grpc

[![Clojars Project](https://img.shields.io/clojars/v/com.github.bpalermo/clj-grpc.svg)](https://clojars.org/com.github.bpalermo/clj-grpc)

gRPC for Clojure on **non-shaded Netty**, over
[clj-protobuf](https://github.com/bpalermo/clj-protobuf): services and clients
built dynamically from
[protoc-gen-clojure](https://github.com/bpalermo/protoc-gen-clojure) generated
code — plain functions in, protobuf Messages on the wire.

```clojure
;; deps.edn
com.github.bpalermo/clj-grpc {:mvn/version "0.1.10"}
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

## Native image

The library is native-image ready, and CI round-trips a JVM client against a
GraalVM binary on every run. The contract that makes it work: every
Netty-touching construction lives in one leaf namespace (`clj-grpc.impl.netty`)
that the API namespaces load through `requiring-resolve` at first construction,
so under `--initialize-at-build-time` nothing Netty-marked initializes during
image build. The jar ships the `META-INF/native-image` config that goes with
that — run-time-init for the leaf, `io.grpc.netty` and `io.netty.handler.ssl`,
plus the reflection entries the runtime require needs — and `native-image`
discovers it automatically. `lazy_netty_test` fails the build if a Netty
reference ever escapes the leaf.

Two consumer caveats: every namespace in the image must be AOT-compiled, since
a native image has no Clojure compiler; and generated code should run the
embedded-descriptor arm, because the class-hinted arm leans on protobuf-java
reflection that would need extra registration.

`bazel build //examples:echo_native` builds the example server as a binary.

## Performance

Choices, with the measurements behind them in
[`docs/performance.md`](docs/performance.md):

- **Handlers run on virtual threads.** Keep that default unless you know the
  shape: `:executor :direct` wins on 1-CPU pods and many-connection clients,
  virtual threads win for a single-connection client on a multi-core pod. A
  handler that blocks on a direct executor stalls every connection on its event
  loop, which is why the safe setting is the default.
- **Generate streaming services with `interop=true`** — protoc-gen-clojure's
  typed paths are worth real capacity and CPU on streams, and latency on unary.
- **Four server options move streaming materially** on a container-shaped pod:
  `:inbound-credits`, `:worker-threads`, `-Xmn`, and
  `:initial-flow-control-window`.
- **Against an ordinary Clojure REST stack** this is roughly 3× on loopback and
  about an order of magnitude on the cluster — where the campaign record lives
  in [`docs/soak-results.md`](docs/soak-results.md), with raw per-step tables in
  [`docs/results/`](docs/results/).
- **Native image or JVM** is a workload question, not a ranking: 79 ms
  time-to-first-RPC and half the memory against a warm JIT's throughput, and
  the two swap places between loopback and production body sizes.

## Building

Bazel (with [rules_clj](https://github.com/bpalermo/rules_clj)):
`bazel test //...` — the e2e suite runs every streaming shape over real Netty,
TCP and UDS both. `clojure -X:test` runs the non-Bazel subset; the Clojars
artifact is `bazel build //src:clojars`, and `bazel run //src:clojars.publish --
--dry-run` prints every upload it would make.

## License

Apache-2.0
