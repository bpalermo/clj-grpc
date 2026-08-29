# Examples

The full path, end to end: `.proto` → protoc-gen-clojure generated namespace →
clj-grpc server with plain-fn handlers → clj-grpc client calls. One service,
one method per streaming shape:

| method | shape | handler signature | call result |
|---|---|---|---|
| `Say` | unary | `(fn [req] resp)` | the reply, blocking |
| `Repeat` | server streaming | `(fn [req send!])` | lazy seq of replies |
| `Summarize` | client streaming | `(fn [respond!]) -> {:on-next ... :on-complete ...}` | `{:send! :close! :response}` |
| `Converse` | bidi | `(fn [send! close!]) -> {:on-next ...}` | `{:send! :close!}`, replies via your observer map |

## Run it

```sh
bazel run //examples:server
# echo server listening on port 8080
```

In another terminal:

```sh
bazel run //examples:client
# say -> "echo: hello"
# repeat -> ["again #1" "again #2" "again #3"]
# summarize -> {:message-count 3, :char-count 11}
# converse -> ["you said: ping" "you said: pong"]
```

The server takes the Knative preset (`clj-grpc.knative`): h2c on `$PORT`
(default 8080), health and reflection on — so grpcurl works too:

```sh
grpcurl -plaintext localhost:8080 list
grpcurl -plaintext -d '{"text": "hi"}' localhost:8080 example.echo.Echo/Say
```

## The pieces

- [`proto/example/echo/echo.proto`](proto/example/echo/echo.proto) — the
  service, one method per streaming shape.
- [`gen/example/echo/echo.clj`](gen/example/echo/echo.clj) — checked-in
  protoc-gen-clojure output: a defrecord per message, `X->proto` / `proto->X`
  at the edges, and the two service defs (`Echo`, `echo-methods`) that the
  server and client consume. Regenerate with
  [protoc-gen-clojure](https://github.com/bpalermo/protoc-gen-clojure) on
  `PATH`:

  ```sh
  cd examples/proto && protoc --clojure_out=../gen example/echo/echo.proto
  ```

- [`src/example/echo/server.clj`](src/example/echo/server.clj) — every handler
  shape, worked. Handlers are plain functions over plain data; the generated
  conversion fns sit only at the edges.
- [`src/example/echo/client.clj`](src/example/echo/client.clj) — every call
  shape, worked: the blocking unary call, the lazy server-stream seq, the
  client-stream `{:send! :close! :response}` controls, and the bidi observer
  map.
- [`test/example/echo/example_e2e_test.clj`](test/example/echo/example_e2e_test.clj) —
  the example server on an ephemeral port, the example client against it,
  every shape asserted. Runs on every `bazel test //...`, so the example
  cannot drift from the library.

`//examples/proto:echo_java_proto` puts protoc's Java classes on the classpath
so the generated namespace resolves its class hints; drop that dep and
everything still runs on the DynamicMessage arm — a plain Clojars consumer
needs no protoc Java output at all.
