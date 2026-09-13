# Kubernetes: probes and shutdown

How the health service, `clj-grpc.knative/shutdown-hook!` and the kubelet fit
together — and the one place where wiring them the obvious way causes a
restart storm.

## What the server exposes

Every server built with `:health true` (the default, and the Knative preset)
registers grpc-java's `HealthStatusManager`, serving
`grpc.health.v1.Health/Check` and `/Watch`. Kubernetes has spoken that protocol
natively since 1.24 (GA in 1.27): a `grpc:` probe makes the kubelet call
`Check` with a service name and treats `SERVING` as success, anything else —
`NOT_SERVING`, `SERVICE_UNKNOWN`, connection refused — as failure. No sidecar,
no `grpc_health_probe` binary in the image.

Two things set what it answers:

- **`clj-grpc.health/set-status!`** — the 2-arity sets the overall status
  (service name `""`, what probes ask about by default); the 3-arity sets a
  named service.
- **`clj-grpc.server/shutdown`** — enters the manager's terminal state, which
  flips *every* registered service to `NOT_SERVING` and ignores later
  `set-status!`. `shutdown-hook!` does this the instant SIGTERM lands, before
  the drain delay.

## Readiness: the status the app declares

Readiness means "route traffic to me now", which is exactly what the app
declares through the health service. Probe the overall status:

```yaml
readinessProbe:
  grpc:
    port: 8080
    service: ""          # the overall status; set-status!'s 2-arity
  periodSeconds: 5
  failureThreshold: 1    # one NOT_SERVING is a decision, not a blip
```

That gives two behaviours for free:

- **On SIGTERM** the hook flips `NOT_SERVING` at once, so the next probe pulls
  the pod from endpoints while the listener is still open for the drain
  delay. That is belt-and-braces with the Terminating removal, and it is what
  makes the delay meaningful for anything that routes on health rather than
  on endpoints — Envoy, a mesh, Knative's activator.
- **On dependency loss** the app can stop advertising itself:
  `(health/set-status! srv :not-serving)` while the database is unreachable,
  `:serving` when it is back. Traffic drains to healthy replicas without a
  restart. And because the terminal state ignores later `set-status!`, app
  logic flipping back to `SERVING` mid-drain cannot re-admit traffic.

Per-service names work the same way for a server hosting several services:
`(health/set-status! srv "acme.greeter.Greeter" :not-serving)`, probed with
`service: acme.greeter.Greeter`.

## Liveness: not the same signal

This is the trap. Liveness means "restart me". If it probes the same status as
readiness, a dependency outage becomes a restart storm: the database goes
away, every replica reports `NOT_SERVING`, and the kubelet kills all of them
for a condition restarting cannot fix.

Two honest options, and they detect different failures.

**A dedicated health name the app never flips.** Register it once at startup
and probe it. It answers `SERVING` as long as a Netty worker loop can still run
a handler — which is the "wedged process" liveness exists to catch:

```clojure
(-> (knative/server {:services [...]})
    server/start
    (health/set-status! "live" :serving))   ; registered once, never touched again
```

```yaml
livenessProbe:
  grpc:
    port: 8080
    service: live
  periodSeconds: 10
  failureThreshold: 3
```

The name **must** be registered: an unknown service answers `SERVICE_UNKNOWN`,
which fails the probe, and the pod restart-loops from its first check.

**A TCP probe on the port.** Cheaper, and it cannot restart-storm — but it only
proves the acceptor is alive. A stuck worker pool passes it.

## The budget rule

`enterTerminalState` flips the liveness name too, and after `.shutdown()` the
listener closes while in-flight calls finish — so a liveness probe *will* fail
during a drain. Its budget decides whether the kubelet kills the container
early or lets the drain complete:

```
failureThreshold × periodSeconds  >  drain-delay-ms + grace-ms
terminationGracePeriodSeconds     ≥  drain-delay-ms + grace-ms + margin
```

With the preset defaults (2 s + 20 s = 22 s), the liveness YAML above gives
30 s and the Kubernetes default `terminationGracePeriodSeconds: 30` leaves
8 s. Shrink the grace period and the hook's budgets must shrink with it —
`shutdown-hook!`'s docstring states the relation, but nothing enforces it
across the two files. Make it explicit rather than relying on how the kubelet
treats probes on a terminating pod.

## Startup

A JVM cold start here is ~1.7 s, so liveness rarely needs a `startupProbe`.
When a heavier application namespace load pushes past `initialDelaySeconds`,
the fix is a startup probe on the `live` name with a generous
`failureThreshold` — not a longer liveness delay, which would also delay
detecting a real hang. The native image starts in ~79 ms and needs none of
this; it *does* need `--install-exit-handlers` to run the hook on SIGTERM at
all, which this library's jar-shipped native-image config sets.

## Knative

The queue-proxy does the probing there, and gRPC-typed probes in the Revision
spec depend on the Serving version — check yours. Where available, the same
`readinessProbe: grpc:` shape applies; otherwise a TCP readiness probe is the
fallback and the health service still matters for the activator and for mesh
routing. The hook's drain delay is the part Knative cannot do for you: it
covers the gap between "told to stop" and "no longer routed to", which exists
regardless of who probes. Name the container port `h2c` — see the
`clj-grpc.knative` docstring.

## A whole Deployment, for copying

```yaml
spec:
  terminationGracePeriodSeconds: 30
  containers:
    - name: greeter
      ports:
        - name: h2c
          containerPort: 8080
      readinessProbe:
        grpc: { port: 8080, service: "" }
        periodSeconds: 5
        failureThreshold: 1
      livenessProbe:
        grpc: { port: 8080, service: live }
        periodSeconds: 10
        failureThreshold: 3
```

Paired with, in the application:

```clojure
(let [srv (-> (knative/server {:services [greeter-service]})
              server/start
              (health/set-status! "live" :serving))]
  (knative/shutdown-hook! srv)          ; 2 s drain delay, 20 s grace
  (server/await-termination srv))
```
