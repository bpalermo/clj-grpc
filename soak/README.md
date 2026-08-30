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

## Deploying

The harness is a Helm chart (`charts/clj-grpc-soak`) built by Bazel — image
digests in values.yaml are stamped at package time from the images the same
build graph produced, so there is nothing to pin and no registry to consult:

```sh
# pushes the three images, then installs the chart pinned to those digests
bazel run //charts:soak.install -- --namespace clj-grpc-soak --create-namespace
```

`KUBECONFIG` passes through to the hermetic helm runner. IMPORTANT: run
installs from a host matching the cluster architecture (talos-main is
arm64) or from the CI-published chart — a locally built x86 image set would
be pushed and pinned. The chart itself is also published to
`oci://ghcr.io/bpalermo/clj-grpc/charts` by build.yaml on main; cluster-side
installs can use that artifact directly:

```sh
helm install clj-grpc-soak oci://ghcr.io/bpalermo/clj-grpc/charts/clj-grpc-soak \
  --namespace clj-grpc-soak --create-namespace
```

## Running

Per-run load objects are chart values, off by default — a run is an
invocation, not standing state:

```sh
# fixed-rate soak (k6 TestRun)
bazel run //charts:soak.upgrade -- --set testRun.enabled=true,testRun.rate=200,testRun.duration=2h
# streaming capacity ramp (driver Job)
bazel run //charts:soak.upgrade -- --set streamJob.enabled=true,streamJob.target=grpc-jvm
# back to idle
bazel run //charts:soak.upgrade
```

k6 metrics flow through the cluster's OTel collector into Prometheus with
the `k6_` prefix, split per arm by the `scenario` tag; the stream driver's
results are its Job logs.

## Grading

Against Prometheus (`prometheus-server.prometheus.svc`):

- latency p50/p95/p99 per scenario over time; **drift** = last 15 min vs
  first 15 min (pass: p99 growth < 10%)
- `k6_checks` rate (pass: > 99.9%), delivered iteration rate = target RATE
- `container_memory_working_set_bytes` per pod: plateau, no monotonic growth
- `rate(container_cpu_usage_seconds_total)` per pod ÷ delivered rate =
  **CPU per request** per arm
- pod restarts / OOMKills (pass: zero)

## Streaming capacity (bidi echo)

The unary ramps measure per-call cost; `clj-grpc.soak.stream-driver`
measures per-MESSAGE cost over persistent bidi Chat streams — S streams, an
absolute-schedule open-loop pacer (coordinated-omission-safe), bounded
in-flight per stream (`deferred` counts are the knee signal), and
client-side per-message latency from a send-timestamp FIFO (gRPC's
per-stream ordering makes echo k answer send k). k6's stream API was
evaluated and rejected as a high-rate driver (reflective JSON⇄proto per
message, no backpressure, per-stream-only latency); the driver rides inside
the soak-grpc-jvm image's jar, so no extra image exists:

```sh
# per run: set the arm's EXECUTOR env as desired, pin the Job image digest,
kubectl --context talos-main apply -f soak/k8s/stream-capacity-job.yaml
kubectl --context talos-main -n clj-grpc-soak logs -f job/stream-capacity
kubectl --context talos-main -n clj-grpc-soak delete job stream-capacity
```

## Results

Campaign results live in [`docs/soak-results.md`](../docs/soak-results.md);
raw per-step tables in [`docs/results/`](../docs/results/).
