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
digest (edit the `images:` block with the digests build.yaml printed — that
is what makes a run reproducible later), places one arm per worker, and
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

_(filled in after each soak)_
