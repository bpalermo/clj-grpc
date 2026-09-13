# Pre-registered — the ladder refreshed on the shipped chart (0.2.27), 2026-09-13

Every figure in the ladder table predates chart 0.2.23's defaults
(`-Xmn256m`, `:inbound-credits 8`) and the typed-slot read path (0.2.27).
Same ramps as the 2026-09-11 medians (extended one step where the knee
moved), `:direct`, realistic tier, 1-CPU pods, node CPU sampled on every
host, driver throttling read from the Job cgroup; gRPC rungs ×3 for
medians, REST rungs ×2 (their levers moved ≤5%). Aligned with the aether
session's soak on talos-main before starting.
- L1 gRPC unary median ~8,300 (the 0.2.23 number, +27% on 6,540); the
  typed read path adds ≤3% on unary (nil on interop's unary too).
- L2 gRPC stream median 16,000–16,500 (the loop cap ~0.88 core; the typed
  reads' −6–9% per message on x86 one connection may buy a few percent).
- L3 REST h1 ~835, h2c ~840 (young-gen +5%).
- L4 the ratios become ~10× (unary) and ~19–20× (stream) over REST h1.
Replicate floor 0.1–4%; a rung outside its band by more than that is read
against the node CPU before it is believed.
