# clj-grpc

gRPC for Clojure on **non-shaded Netty**, over
[clj-protobuf](https://github.com/bpalermo/clj-protobuf): services and clients
built dynamically from
[protoc-gen-clojure](https://github.com/bpalermo/protoc-gen-clojure) generated
code — plain functions in, protobuf Messages on the wire.

```clojure
;; deps.edn
com.github.bpalermo/clj-grpc {:mvn/version "0.1.0"}
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

## Building

Bazel (with [rules_clj](https://github.com/bpalermo/rules_clj)):
`bazel test //...` — the e2e suite runs every streaming shape over real Netty,
TCP and UDS both. `clojure -X:test` runs the non-Bazel subset; the Clojars
artifact is `clojure -T:build jar`.

## License

Apache-2.0
