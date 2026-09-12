# Pre-registered — native tiny tier, 2026-09-12

Question: is the native arm's 3–4× per-request cost gap at 1 KB the payload
path or the platform? Same digest as 2026-09-11-native
(soak-grpc-native@sha256:76b6edb7…), chart 0.2.22, 1-CPU pods, tiny tier
(7 B pb), Nighthawk one worker; the JVM `:direct` arm re-run on the tiny tier
the same day so the ratio is same-day, same-chart.

- If native/JVM capacity on tiny is ≥ 0.7× (against 0.29–0.34× on realistic),
  the gap is the payload path: codec and value work under AOT, and a
  native-aware codec could narrow it.
- If it is ≤ 0.45×, the gap is the platform (AOT code quality, Serial GC,
  no JIT) and payload work would not move it.
- Between: mixed; report both terms.
Ramps run past the knee; node CPU sampled on both hosts; driver throttling
read from the Job cgroup.
