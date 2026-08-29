# clj-grpc — design

gRPC for Clojure, layered on
[clj-protobuf](https://github.com/bpalermo/clj-protobuf): services and clients
built dynamically from what
[protoc-gen-clojure](https://github.com/bpalermo/protoc-gen-clojure) already
emits, with plain functions as handlers and protobuf `Message`s on the wire.
No stub generation, no macros: a generated file's entire service surface is

    (def Greeter (rts/service file-descriptor "Greeter"))
    (def greeter-methods (rts/methods-map Greeter))

and everything else — method names, streaming shapes, marshallers, grpc-java
`MethodDescriptor`s — is derived from the `FileDescriptor` at load time.

## The service value

`service` returns a record of `Method` records, one per RPC in declaration
order: proto name, kebab key, streaming type (`:unary` / `:server-streaming` /
`:client-streaming` / `:bidi`), request/response prototypes, and a *delayed*
`io.grpc.MethodDescriptor` — delayed so building a Service never constructs
grpc machinery a caller that only wanted the shapes will not use.
`methods-map` keys them by kebab keyword; that map is the bridge both the
server and the client consume, and the only coupling between them.

## Pool discipline, inherited

clj-protobuf's rule — never mix descriptor pools — lands here with force: a
marshaller's prototype decides which pool parsed requests live in, and
handlers hand those requests straight to the generated `proto->X` fns, whose
handles live in the *namespace's* pool. So `service` resolves method
prototypes exactly the way the emitter's hints do: derive the Java class name
protoc would generate (only for `java_multiple_files` or edition-2024
top-level classes — the same subset the plugin hints), verify it describes the
same message, fall back silently to `DynamicMessage` **over the same
`FileDescriptor` instance the namespace uses**. Both arms align: with the
generated classes present, everything is the generated pool; without them,
everything is the embedded pool. The service tests pin the property; the e2e
suite exercises it over the wire.

## Non-shaded Netty, deliberately

`grpc-netty-shaded` hides the version-alignment problem but rewrites package
names, making `netty-transport-native-epoll` unreachable — and epoll is how
both Unix domain sockets and the fast path work. So this library takes the
alignment problem on, explicitly:

- grpc-java's SECURITY.md pairing table is the authority (grpc 1.83.1 ↔ Netty
  4.2.16.Final); `deps.edn` pins **every** netty artifact top-level, because
  tools.deps gives top-level pins absolute precedence over grpc's pom ranges.
- `netty_alignment_test` asserts every loaded netty jar reports the pinned
  version and that `NettyChannelBuilder` links. It caught real drift before
  the first commit — grpc-netty 1.83.1's own pom pulls `netty-codec-socks`
  and `netty-handler-proxy` at 4.2.15 — which is precisely the failure class
  non-shaded Netty threatens, converted into a CI failure forever.
- Bumping grpc is therefore a ritual: consult the pairing table, bump the
  whole pin block, let the test judge.

One knock-on: the lockfile records no dependency edges for top-level-pinned
artifacts (rules_clj's `:dependents` inversion has nothing at the root), so
`src/BUILD.bazel` lists every netty jar explicitly rather than trusting
transitivity. A rules_clj follow-up may remove the need.

## Transport

Netty 4.2's event-loop API (`MultiThreadIoEventLoopGroup` over an `IoHandler`
factory), epoll preferred, NIO fallback, `:transport :auto|:epoll|:nio` with
eager, diagnosed failure when epoll is demanded but absent — and eager
rejection of UDS-on-NIO, because a bind-time surprise is worse than an
analysis-time one.

Event-loop threads are **daemon**, like grpc-java's own defaults. The
alternative pins any JVM that does not end in `System/exit` — every REPL,
every `clojure -X` — and surfaced exactly that way: tests green, process
immortal, caught by the plain-clj CI leg on its first run.

Unix domain sockets work in both directions (`{:unix path}` /
`"unix:///path"`), epoll-only, with the socket-path length limit (~108 bytes)
respected in tests by binding under `/tmp` — hermetic per action in Bazel's
Linux sandbox — never `TEST_TMPDIR`.

## Server

A `ServerServiceDefinition` is built dynamically from the methods map; each
handler fn is adapted per streaming shape via `ServerCalls` (unary
`(fn [req] resp)`; server-streaming `(fn [req send!])`; client-streaming
`(fn [respond!]) -> observer-map`; bidi `(fn [send! close!]) -> observer-map`).
Methods without handlers are simply omitted and answer UNIMPLEMENTED — gRPC's
own semantics, not a reimplementation. Thrown exceptions become
`Status/INTERNAL` with the message attached; throwing a
`StatusRuntimeException` controls the status. Health
(`HealthStatusManager`, default on) and reflection (v1, opt-in) are wired as
grpc-services instances, not reimplemented.

Executor choice is measured, not asserted: `:executor :direct` (the Netty
event loop) cuts unary latency ~29% and loses ~9% throughput at 32-way
concurrency — the load mode of the benchmark exists precisely because the
sequential lens inverts under load. The per-call machinery floor (~190 µs)
also anchors the strongest guidance this library can give: one stream beats N
small unaries by two orders of magnitude, before any tuning.

Handlers run on virtual threads by default: Clojure handlers block — that is
the model — and grpc's default shared pool is sized for handlers that never
do. `:executor` overrides; a server-owned executor is closed on shutdown.

Keepalive is two-sided and the sides must agree: gRPC servers reject pings
more frequent than `permitKeepAliveTime` (default five minutes) with
`GOAWAY too_many_pings`, so `:permit-keepalive` is exposed and the Knative
presets pair the client's 30-second pings with a matching server permit — a
pairing pinned by its own test, because presets that fight each other are
worse than no presets. Shutdown enters the health service's terminal
NOT_SERVING state before the listener closes, the drain order rollouts
assume; `clj-grpc.health` exposes status transitions as functions.

Plaintext (h2c) is the default and TLS the option — the reverse of grpc-java's
posture, because the deployment target is a mesh/Knative world where the
platform owns transport security and h2c is what the ingress speaks.

## Client

Channels take `"host:port"`, `dns:///` targets, or UDS forms; calls come from
the same methods map — `invoke` per call or `client` for the whole service as
a map of fns. Blocking shapes block (unary → response, server-streaming →
seq); streaming-in shapes return `{:send! :close! :error!}` controls plus a
promise (client-streaming) or deliver into the caller's observer map (bidi).
Deadlines and wait-for-ready ride per-call opts.

## Knative

`clj-grpc.knative` is presets, not machinery: the server preset is h2c on
`$PORT` with health and reflection on (and the README documents naming the
container port `h2c`); the client preset is plaintext + wait-for-ready +
keepalives — the activator-in-path, scale-from-zero posture where the first
request must tolerate a pod that is still being summoned.

## Cold start, and the Netty leaf

Native-image works, and the design that unblocked it is now load-bearing API
structure. The collision was precise: Clojure-compiled code initializes
referenced classes eagerly (fn-class `<clinit>` → `RT.classForName`
initialize=true) at namespace load — build time under
`--initialize-at-build-time` — while Netty's shipped native-image metadata
mandates run-time init for its native/Unsafe-touching classes, correctly. The
resolution: every Netty and grpc-netty construction lives in one leaf
namespace, `clj-grpc.impl.netty`, which transport/server/client reach only
through `requiring-resolve` at first construction. The leaf's classes are
marked initialize-at-run-time (package `clj_grpc.impl`) and its init class is
registered for reflection so the runtime `require` can find it; the class
*values* it resolves (the six channel classes) are the only other reflection
entries needed, because the leaf deliberately avoids `:import` — imports
intern through `Class.forName`, fully-qualified interop compiles to direct
method references. The jar ships this as auto-discovered
`META-INF/native-image` config, which also fills two upstream gaps grpc-netty
leaves open (it publishes no metadata; only grpc-netty-shaded does):
`io.grpc.netty` wholesale, because `NettyServerBuilder.<clinit>` probes
`Epoll.<clinit>` — a JNI load — at class init; and `io.netty.handler.ssl`,
because `JdkSslServerContext.<clinit>` parses a PEM and allocates ByteBufs.

`lazy_netty_test` pins both halves of the contract on the JVM: requiring the
API namespaces must not load the leaf, and no source file outside the leaf
may mention a Netty package. On the JVM the indirection is behaviorally
inert — the leaf loads at first construction, event loops, UDS, everything
as before.

Measured with the `//bench:coldstart` harness (spawn-to-first-success, warm
prober, fresh channel per probe — a reused channel's reconnect backoff
quantizes the reading, which is also why two earlier numbers moved): plain
jar ~1.75 s, AppCDS ~1.71 s, native image ~79 ms — 22×, and the binary
serves Unix domain sockets through the embedded epoll JNI transport. The
honest prober withdrew the earlier −57% AppCDS claim: this startup is
dominated by running Clojure's class initializers, which CDS cannot skip.
Native-image caveats for consumers: AOT everything (no compiler in the
image), and prefer the embedded-descriptor arm of generated code — the
class-hinted arm rides protobuf-java's reflection, which wants per-message
registration the library does not ship.

## Measured, not asserted

Two benchmarks, both with always-on smoke tests so they cannot rot:
clj-protobuf's serialization corpus, and this repo's RPC benchmark — full
round trips on loopback against the ordinary Clojure REST stack (Pedestal on
Jetty, jsonista both sides, JDK HttpClient), identical echo semantics asserted
before anything is timed. ~2.8× at every payload size, framing-dominated
through bytes-dominated; the README carries the table.

## Build and release

Identical discipline to clj-protobuf: Bazel + rules_clj primary with the
bazel 8/9 × plain-clj CI matrix under one aggregate check, `deps.edn` as the
single dependency source, source-only Clojars jar via `build.clj`,
version-consistency and formatting as ordinary tests, tag-gated `release`
environment, and a release workflow that refuses tags not on main or
disagreeing with `version.edn`.

## Non-goals

- **Stub/interface generation.** The methods map plus plain fns *is* the API;
  anything more is sugar someone can build on top.
- **An async/blocking abstraction layer.** Blocking where gRPC blocks,
  observer-shaped where gRPC streams; core.async/manifold adapters belong in
  wrapper libraries with their own dependency choices.
- **TLS machinery.** `:tls` covers cert/key; tcnative/OpenSSL tuning is
  documented as an add-on, not depended on — h2c deployments need none of it.
- **Shaded-Netty compatibility.** The entire point is the native transports;
  a consumer who wants grpc-netty-shaded wants a different library.
- **grpc-web, xDS, retries-as-policy.** Grow on demand, behind the same
  alignment discipline.
