# Soak: gRPC native vs gRPC JVM vs REST, on a cluster

The loopback benchmarks (README: cold start, steady state, density) answer
what each arm costs; a soak answers how each arm *holds* — latency drift,
memory growth, GC behavior, restarts — under hours of fixed load on real
hardware over a real network. Three arms, identical echo semantics, identical
1-CPU/1Gi Guaranteed pods, one arm per worker, driven concurrently by one k6
runner on its own worker:

| arm | image | server |
|---|---|---|
| `grpc-native` | `ghcr.io/bpalermo/clj-grpc/soak-grpc-native` | `//bench:coldstart_native` (GraalVM, `-Xmx512m -Xmn256m`) |
| `grpc-jvm` | `ghcr.io/bpalermo/clj-grpc/soak-grpc-jvm` | the same server's deploy jar (`-Xmx512m`, G1) |
| `rest` | `ghcr.io/bpalermo/clj-grpc/soak-rest` | `clj-grpc.soak.rest-server` (Pedestal/Jetty + jsonista) |

## Images

Built by `.github/workflows/build.yaml` on **arm64** runners: every PR builds
all three (`bazel build //bazel/images:..._image`); pushes happen only on
main, and only for images whose OCI manifest digest differs from the
registry's `latest` — unchanged inputs, no push, no tag churn. Tags:
`<git-commit>` and `latest`. Bases and rules come from `bazel/images/BUILD.bazel`
(rules_img; no Dockerfiles).

## Deploying (talos-main)

```sh
kubectl --context talos-main apply -k soak/k8s/overlays/talos-main
kubectl --context talos-main -n clj-grpc-soak get pods -w   # wait Ready
```

The base (`soak/k8s/base`) is cluster-agnostic; the overlay pins images by
digest — that is what makes a run reproducible later. `./soak/pin-digests.sh`
stamps the pins from the registry's current `latest` in one command (the same
digests build.yaml prints in its run summary); commit the stamped overlay
with the soak's results. The overlay also places one arm per worker and
carries the k6 script + wire-compatible proto3 copy of the greeter proto
(k6 cannot parse edition 2024) as a ConfigMap.

## Running

```sh
# start (edit RATE / DURATION in the TestRun first if needed; defaults 200/s per arm, 2h)
kubectl --context talos-main apply -f soak/k8s/overlays/talos-main/testrun.yaml
# watch
kubectl --context talos-main -n clj-grpc-soak get testrun clj-grpc-soak -w
# clean up the run (servers stay for the next one)
kubectl --context talos-main -n clj-grpc-soak delete testrun clj-grpc-soak
```

k6 metrics flow through the cluster's OTel collector into Prometheus with the
`k6_` prefix, split per arm by the `scenario` tag (`grpc_native`, `grpc_jvm`,
`rest`). Smoke first: apply the TestRun with `DURATION=1m`, `RATE=50`, confirm
zero check failures and metrics in Prometheus, delete, then run the real one.

## Grading

Against Prometheus (`prometheus-server.prometheus.svc`):

- latency p50/p95/p99 per scenario over time; **drift** = last 15 min vs
  first 15 min (pass: p99 growth < 10%)
- `k6_checks` rate (pass: > 99.9%), delivered iteration rate = target RATE
- `container_memory_working_set_bytes` per pod: plateau, no monotonic growth
- `rate(container_cpu_usage_seconds_total)` per pod ÷ delivered rate =
  **CPU per request** per arm
- pod restarts / OOMKills (pass: zero)

## Results

### 2026-08-29/30 — executor grid + capacity campaign

**Fixed-rate grid** (30 min per scenario, 200 req/s per arm, ~4.6M requests,
zero check failures): the executor choice is a CPU/median-vs-tail trade —
gRPC `:executor :direct` cut CPU 35% (386→269m native, 221→145m JVM) and won
every p50, but roughly doubled p99 at this low utilization (event-loop
convoying with deferred flushes); Jetty's virtual-thread dispatch cost
nothing on any axis (one dispatch per request either way). Memory was
invariant to threading everywhere. GC logs showed 1-CPU pods get Serial GC
by JVM ergonomics; native's live heap ran ~9 MB.

**Capacity ramps** (200→2400 req/s, 2-min steps, one arm at a time, same
pods): max sustainable goodput per identical 1-CPU pod —

| configuration | plateau | overload behavior |
|---|---|---|
| gRPC JVM `:direct` | ~2,140 req/s | graceful (h2 flow-control backpressure) |
| gRPC JVM VT default | ~2,060 req/s | graceful, tails degrade |
| gRPC native VT | ~1,550 req/s | liveness-starvation kills before probe tuning |
| REST (Jetty, HTTP/1.1) | ~960 req/s | queue-death: p50 pins at 1.7 s, then collapse |

At equal resources gRPC sustains **2.2× (JVM) / 1.6× (native)** REST's
throughput. The executor trade **inverts with load**: VT protects tails ~2×
at ~10% utilization; above ~75% utilization `:direct` wins goodput *and*
tails (p99 382 vs 662 ms at 2000 req/s). Peak heaps under saturation:
native 81 MB, gRPC-JVM 61 MB, REST 267 MB.

A grpc-netty patch adding a server-side `WriteQueue.drainNow()` (upstream
prototype) reproduced its JMH signature on-cluster: p99 −20–23% across
1200–2200 req/s and **−64% at 2400** (1994→717 ms), goodput unchanged.

Operational lessons baked into these manifests: liveness probes need
saturation tolerance (a pegged 1-CPU pod starves its probe and the kubelet
kills a healthy server — measured twice); the k6-operator initializer
inherits the runner's nodeSelector and resources, so an unschedulable
runner spec deadlocks silently in `initialization`; JVM arms need their
first ramp minutes excluded as JIT warmup, native arms don't.
