# Interceptors

How to see, shape and refuse calls without touching a handler — on the
server, on the client, and from inside a handler. Everything here is
exercised by `test/clj_grpc/interceptor_test.clj`, and every recipe is real
code in [`examples/src/example/echo/interceptors.clj`](../examples/src/example/echo/interceptors.clj),
round-tripped by its test. The *why* — Context, ordering, `proxy`, what is
deliberately not sugared — is in [`design.md`](design.md#interceptors).

## One shape, both sides

An interceptor is a function of the call and a continuation:

```clojure
(fn [call next] ...)
```

`call` is a map describing the call; `next` continues the chain. The
interceptor returns what `next` returns — passing the map through as it is,
or enriched — or, on the server, a rejection. It goes in an `:interceptors`
vector:

```clojure
(server/server  {:services [...] :interceptors [a b c]})
(client/channel target {:interceptors [a b c]})
(client/invoke  ch method req {:interceptors [d] :headers {"x-id" "1"}})   ; this call only
```

Raw `io.grpc.ServerInterceptor` / `ClientInterceptor` values go in the same
vector and are ordered with the rest. **`[a b c]` runs `a` outermost** — first
on the way in, last on the way out — on every path. (grpc's own builders run
the last-registered interceptor outermost; the library reverses the vector at
registration so the order you wrote is the order that runs.) A per-call
interceptor sits outside the channel's.

## Server

### The call map

| key | value |
|---|---|
| `:method` | the method's kebab keyword, `:say-hello` — the same key as your handlers map |
| `:service` | `"acme.greeter.Greeter"` |
| `:full-method-name` | `"acme.greeter.Greeter/SayHello"` |
| `:headers` | the request headers, an `io.grpc.Metadata` — read with `clj-grpc.metadata/header` |
| `:authority` | what the client sent as `:authority`, or nil |
| `:peer` | the client's `java.net.SocketAddress`, or nil |
| `:deadline` | an `io.grpc.Deadline`, or nil when the client set none |
| `:method-descriptor` `:attributes` `:server-call` | the grpc objects, for anything above does not cover |

Headers stay a raw `Metadata` on purpose: converting every header of every
call into a map would cost on each request, and most interceptors read one
name.

### Pass through, enrich, refuse

```clojure
(require '[clj-grpc.interceptor :refer [reject]]
         '[clj-grpc.metadata :as metadata])

(defn require-token [token]
  (let [expected (str "Bearer " token)]
    (fn [call next]
      (if (= expected (metadata/header (:headers call) "authorization"))
        (next (assoc call :user "token-holder"))            ; enrich: the handler sees :user
        (reject :unauthenticated "missing or bad token"      ; refuse: no handler runs
                {"www-authenticate" "Bearer"})))))
```

What you `assoc` before calling `next` is what inner interceptors and the
handler see. `reject` takes a status — a keyword (`:unauthenticated`,
`:permission-denied`, `:not-found`, any of grpc's codes kebab-cased), a
`Status$Code`, or a `Status` — a description, and trailers as a map. It is a
**value**, not an exception: the auth-failure path is the common one and
should not pay for a stack trace. Throwing works the way it does in handlers:
a `StatusRuntimeException` keeps its status and trailers, anything else
becomes `INTERNAL` with the message.

### Response headers and trailers

Two keys on the map you give to `next`, each a map or a `Metadata`:

```clojure
(defn stamp [call next]
  (next (assoc call
               :response-headers  {"x-served-by" "pod-7"}
               :response-trailers {"x-cache" "miss"})))
```

They are merged into whatever the handler's response carries. Trailers from
*inside* a handler are the one thing not sugared: the error path already
carries them — throw `(.asRuntimeException (interceptor/status :not-found "no such order") trailers)`.

### Health and reflection go through the chain too

Server interceptors are registered on the builder, so probes and reflection
calls pass through them. `:service` is how to tell them apart:

```clojure
(defn request-log [{:keys [service method peer] :as call} next]
  (when-not (= "grpc.health.v1.Health" service)
    (println (str "call " method " from " peer)))
  (next call))
```

## Inside a handler

Handlers keep their signatures. They read the call through `clj-grpc.context`:

```clojure
(require '[clj-grpc.context :as context])

(fn [req]
  (let [{:keys [user]} (context/call)]              ; the map the interceptors built
    (log/info "served" (context/method) "for" user "from" (context/peer))
    ...))
```

`context/call` is the whole map; `headers`, `header`, `method`, `service`,
`peer` and `authority` are the shortcuts. **The map exists whenever at least
one Clojure interceptor is in the server's chain** — with none, nothing is
built and `call` answers nil. `(fn [call next] (next call))` is the whole cost
of opting in.

Two accessors need no interceptor at all, because they come from grpc's own
per-call context: `(context/deadline)` and `(context/cancelled?)`. A long
handler polls the second to stop working for a client that has gone.

### The one caveat: it is thread-local

grpc attaches the context on the thread that runs each callback — the
virtual-thread executor and `:direct` both. A thread the handler starts itself
does not inherit it:

```clojure
(fn [send! close!]
  (let [ctx (Context/current)]                          ; capture on the callback thread
    (Thread/startVirtualThread
     (.wrap ctx (fn [] (work (context/call)))))         ; sees the map
    (Thread/startVirtualThread
     (fn [] (context/call)))                            ; nil
    {...}))
```

Or read what you need first and pass it along — for a pump like the
core.async example's, that is usually the better shape.

## Client

### The call map

| key | value |
|---|---|
| `:method` `:service` `:full-method-name` `:method-descriptor` | as on the server |
| `:call-options` | the `io.grpc.CallOptions`; replace it to change the deadline, credentials, compression |
| `:headers` | a **map** of the outgoing headers declared so far |
| `:authority` | the channel's |
| `:deadline` | from the call options, or nil |

### Declare headers, watch the response

```clojure
(defn bearer [token on-served-by]
  (fn [call next]
    (next (-> call
              (update :headers assoc "authorization" (str "Bearer " token))
              (assoc :on-headers  (fn [md] (on-served-by (metadata/header md "x-served-by")))
                     :on-trailers (fn [status md] ...))))))
```

Headers are *declared* in the map and written when the call starts, because
grpc creates the outgoing `Metadata` after interceptors run. That is also why
an inner interceptor sees — and may override — what an outer one declared,
and why headers a raw interceptor adds at start are invisible to Clojure
ones. `:on-headers` receives the response headers; `:on-trailers` the final
`Status` and trailers, which is where a timing or error-rate interceptor
lives.

For a header on one call, skip the interceptor:

```clojure
(client/invoke ch (:say-hello methods) req {:headers {"x-request-id" id}})
```

`client/client`'s default opts take the same keys, so a whole service map can
carry a channel's worth of interceptors and headers per call.

### Propagating a header downstream

A client interceptor runs on the thread that makes the call. Inside a
handler, that is the callback thread — so it can read the *incoming* call's
context and forward from it:

```clojure
(defn propagate [name]
  (fn [call next]
    (next (if-let [v (context/header name)]
            (update call :headers assoc name v)
            call))))

;; a channel a handler uses to call the next service
(client/channel "orders:8080" {:plaintext true :interceptors [(propagate "x-request-id")]})
```

No context, no header — the interceptor is harmless on a channel used from
outside a call.

## Metadata helpers

`clj-grpc.metadata` is the only place a `Metadata$Key` is touched:

```clojure
(metadata/header md "authorization")       ; last value, or nil
(metadata/values md "accept")              ; every value, in order
(metadata/metadata->map md)                ; {name last-value}
(metadata/metadata {"x-a" "1" "x-r" ["a" "b"] "x-trace-bin" some-bytes})
```

A name ending in `-bin` is a binary header carrying byte arrays; everything
else is ASCII — grpc's rule, applied for you. Names may be strings or
keywords.

## Recipes, all tested

In [`examples/src/example/echo/interceptors.clj`](../examples/src/example/echo/interceptors.clj):

| fn | side | shows |
|---|---|---|
| `request-log` | server | logging, the health filter, a response header — wired into the example server by default |
| `require-token` | server | rejection with status and trailer, enrichment the handler reads |
| `bearer` | client | declaring a header, watching a response header |
| `timing` | client | per-call latency and status from `:on-trailers` |
| `propagate` | client, inside a handler | forwarding an incoming header to a downstream call |
| `whoami` | handler | reading `:user` and the peer through `clj-grpc.context` |

Run the server and client and watch the log lines: `bazel run //examples:server`,
then `bazel run //examples:client`.

## What is not here, and the one call that gets it

- **Per-method registration.** Wrap the service definition yourself:
  `(io.grpc.ServerInterceptors/intercept (server/service-definition svc) [(interceptor/server-interceptor f)])`
  and add the result with `:services`… or filter on `:method` inside a
  server-wide interceptor, which is one `case` away.
- **Per-message hooks.** That is the handler's shape; a `:bidi` handler's
  `:on-next` is exactly that hook.
- **Automatic context propagation to threads you start.** grpc offers none;
  `(.wrap (Context/current) f)` is the whole mechanism.

## Native image

The forwarding wrappers are `proxy` classes over abstract grpc types.
Clojure's `proxy` expands to a direct `new` of a class that AOT writes to
disk, so the image needs no reflection entries for them — and the example
server wires `request-log` by default so the CI native job proves it on
every change, by round-tripping a client through it against the GraalVM
binary.
