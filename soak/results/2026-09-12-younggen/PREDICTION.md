# Pre-registered — a sized young generation on the cluster's 1-CPU arms, 2026-09-12

The x86 host showed the JVM's Serial default (~5 MB young generation under a
1 GB limit) capping streaming on both executors (+25–70% from `-Xmn256m`).
The cluster's arms are the same shape but 1 CPU, where GC was 5–7% of the
profiles. Chart 0.2.22 unchanged, `-Xmn256m` injected through the arms'
JAVA_TOOL_OPTIONS (composed ahead of direct linking by the chart), `:direct`,
realistic tier, same ramps as the 2026-09-11 medians extended one step.
- Y1 gRPC unary: +5–15% capacity over the median 6,540; nil below +4%.
- Y2 gRPC stream: +5–15% over 14,000; the one-connection loop cap (~0.85
  cores) may bound it — if capacity is unchanged but CPU/msg drops ≥5%, the
  lever is real and the loop is the ceiling.
- Y3 REST h1: within ±4% of 795 (Jetty's allocation rate is lower per
  request and the arm is pipeline-bound).
