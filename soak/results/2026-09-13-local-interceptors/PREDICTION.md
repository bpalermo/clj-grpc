# Pre-registered — interceptors (clj-grpc 0.1.12) on the pinned harness, 2026-09-13

Request from the clj-grpc session's user. Jars: `base.jar` = a9fa10e (0.1.11,
the last commit before interceptors) and `int.jar` = main 58333c0 (0.1.12)
plus a local INTERCEPTOR env knob on the soak server (`interceptor-knob.patch`).
Same pinned harness, chart defaults, typed-slot fixture, VT. Shapes: streaming
one connection (40 streams, the worst case for a per-callback context
attach), streaming eight connections with two loops, unary eight
connections (eight driver workers). Arms per shape, interleaved: A0 base
empty, A1 int empty, B int passthrough `(fn [call next] (next call))`, C int
headers `(next (assoc call :response-headers {"x-a" "1"}))`. CPU per
message from the cgroup counters.
- A: A1 within ±3% of A0 on every shape (nothing registered → no per-call
  diff). Above 3% = a regression, the clj-grpc session's.
- B: unary ≤3% per call (the call map + Contexts/interceptCall against
  ~35 µs of grpc-java machinery); streaming one connection +2–5% per
  message if the attach/detach is per listener callback, ≤1% if per call.
- C: one to three points above B (the forwarding proxy's two dispatches
  and a Metadata merge, per call not per message → smaller on streaming).
Nil below 3%.
