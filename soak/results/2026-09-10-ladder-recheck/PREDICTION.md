# Prediction — recorded before the runs

## Why

The headline ladder — REST h1 ~750 → h2c ~750 → gRPC unary ~4,700 (6×) →
stream ~10,000 msg/s (13×), per core — is what every other conclusion in this
repo cites. It was measured at 1 CPU, before `soak/node-cpu.sh` existed, so
nothing confirms the host had headroom during those runs. The 2-CPU work turned
out to have been host-limited throughout; this checks whether the 1-CPU
foundation has the same defect.

The expectation is that it does not. A 1-CPU arm needs ~1 core plus its kernel
networking on a 4-core node carrying ~1.8 cores of resident load — roughly 2.2
cores of demand against 2.2 available, with none of the doubling that broke the
2-CPU runs. But that is an argument, and the point of this run is to stop
arguing.

## The confound, named in advance

These runs are on **chart 0.2.21** (clj-protobuf 0.2.5). The published ladder
numbers came from the re-baseline on **chart 0.2.8** (clj-protobuf 0.2.2, before
the slot handover and before the monitor removal). So two things differ from the
published runs, not one: the node instrument is new, and the codec has moved two
releases.

That means a **match** is strong — two independent things changed and the number
did not — while a **mismatch** cannot be attributed without further work.

## Readings, fixed in advance

- **Node under ~3.5 of 4 cores at every step, and throughput within ~5% of
  published** → the ladder stands as written, on an unsaturated host, and the
  1-CPU foundation is sound.
- **Node at or near 4.0 at the plateau steps** → the 1-CPU numbers are
  host-limited too, and the headline table needs the same correction the 2-CPU
  work got. This is the outcome that would matter most and I do not expect it.
- **Node has headroom but throughput differs by more than ~5%** → the codec
  moved it, not the host. Interesting, and a separate investigation; the ladder
  would need re-stating against a named chart version rather than corrected.

## Runs

Four rungs, realistic tier, 1 CPU, chart defaults for placement (REST arms on
worker-04, `grpc-jvm` on worker-03), profiling OFF to match the agent-free
images the published numbers came from. Ramps are narrowed to bracket the
published plateau, with a low first step to absorb the ~10% first-step
under-read:

- `rest-h1` / `rest-h2c`: 200 400 600 800 1000 rps  (published plateau ~750)
- `grpc-jvm` unary: 1000 3000 4000 5000 6000 rps    (published ~4,700)
- `grpc-jvm` stream, 40 streams: 4000 8000 10000 12000 14000 msg/s (published ~10,000)

Both candidate nodes are sampled throughout, since the arms sit on different
ones.
