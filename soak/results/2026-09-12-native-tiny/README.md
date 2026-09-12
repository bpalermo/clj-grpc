# Native tiny tier — 2026-09-12

Is the native arm's 3–4× per-request cost gap at 1 KB the payload path or the
platform? Same native digest as 2026-09-11-native (`soak-grpc-native@sha256:
76b6edb7…`), chart 0.2.22, 1-CPU pods, tiny tier (7 B protobuf), one driver
worker, the JVM `:direct` arm re-run on the same tier the same night. Node CPU
sampled on both hosts (`node-worker-02.tsv` native, `node-worker-03.tsv` JVM);
never above 3.2 of 4. Zero restarts. `PREDICTION.md` was written first.

| mode | native | JVM `:direct` | native / JVM | CPU ratio |
|---|---|---|---|---|
| unary, 7 B | ~4,700 rps at 0.213 ms | ~12,300 rps at 0.075 ms | **0.38×** | 2.8× |
| stream, 7 B | ~11,200 msg/s at 0.088 ms | ~47,700 msg/s at 0.018 ms* | **0.23×** | 4.9× |
| unary, 1 KB (2026-09-11) | ~2,240 at 0.45 | ~6,540 at 0.15 | 0.34× | ~3× |
| stream, 1 KB (2026-09-11) | ~4,100 at 0.24 | ~14,000 at 0.06 | 0.29× | ~4× |

*\* the JVM streaming arm was at its one-connection loop cap (0.86 core) and
still delivering at the top step, so ~47,700 is a floor.*

Pre-registered reading: ≤ 0.45× on tiny = platform. Both shapes land there.
Removing the payload changes the ratio from 0.34→0.38 (unary) and 0.29→0.23
(stream): the gap is the AOT platform — code quality, Serial GC, no JIT — not
the codec or value path, and a native-aware codec would not close it.
