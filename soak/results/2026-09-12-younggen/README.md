# A sized young generation on the cluster's 1-CPU arms — 2026-09-12

The x86 lever (`soak/results/2026-09-12-local-vt-levers`) taken to the
cluster: chart 0.2.22 unchanged, `-Xmn256m` injected through the arms'
JAVA_TOOL_OPTIONS (composed ahead of direct linking; verified on the pod),
`:direct`, realistic tier, the 2026-09-11 medians' ramps extended one step,
node CPU sampled on both hosts (never above 3.2 of 4), zero restarts.
`PREDICTION.md` was written first. Baselines are the ladder medians.

| rung | ladder median (Serial, ~5 MB young) | with `-Xmn256m` | capacity | CPU per request | RSS |
|---|---|---|---|---|---|
| gRPC unary | 6,540 rps at ~0.15 ms | **~8,050 at 0.120** | **+23%** | −20% | 175 → 355 MB |
| gRPC stream (one connection) | 14,000 msg/s at ~0.062 | **~16,000 at 0.054** | **+14%** | −12% | 190 → 380 MB |
| REST HTTP/1.1 | 795 rps at ~1.27 | ~835 at 1.19 | +5% | −6% | 295 → 500 MB |

Y1 (+5–15%) exceeded, Y2 held, Y3 (±4%) just outside — a small real effect
on the Jetty arm too. The streaming rung stays at the one-connection loop
cap (~0.88 cores); the gain there is the cheaper message, not more of the
core. Chart 0.2.23 makes the flag the default on every JVM arm; the ladder
table in the doc predates it and says so.
