# Interceptors (clj-grpc 0.1.12) on the pinned harness — 2026-09-13/14

Requested by the clj-grpc session's user: what does 0.1.12's interceptor
support cost when nothing is registered, and what does opting in cost?
`base.jar` = a9fa10e (0.1.11, the last commit before interceptors);
`int.jar` = main 58333c0 (0.1.12) plus a local `INTERCEPTOR` env knob on the
soak server (`harness/interceptor-knob.patch`). Same pinned harness, chart
defaults, typed-slot fixture, virtual threads; CPU per message from the
cgroup counters (`pairs.txt`, `harness/cpu5.py`); `PREDICTION.md` first.
Shapes: streaming on one connection (40 streams — the worst case for a
per-callback context attach), streaming on eight connections with two
loops, unary on eight connections with eight driver workers. The unary set
ran the next morning after the runner hit an unbound variable (`set -u`)
between sets; the streaming rows are from the evening.

Each pair is same-session (streaming rows 2026-09-13 evening, unary rows
2026-09-14 morning after the runner tripped `set -u` between sets).

| arm | stream, 1 conn (µs/msg) | stream, 8 conns | unary, 8 conns (µs/call) |
|---|---|---|---|
| A: 0.1.12 empty vs 0.1.11 | +0.3 to +0.8% | +0.1 to +0.5% | ±0.5% |
| B: one pass-through server interceptor `(fn [call next] (next call))` | ±0.3% | ±0.3% | **+0.5 to +1.2%** at 60k–100k (~0.5 µs per call); +5% at 40k where the call is under-loaded |
| C: one interceptor setting response headers | −0.7 to +0.7% | −0.8%, one +3.9% row at the top | **+0.6 to +1.8%** at 60k–100k (~0.7 µs per call); +7% at 40k |

Reading, all arms within the pre-registered bands:
- **Nothing registered costs nothing** on any shape (A within the floor):
  the "empty vector registers nothing" claim holds under load.
- **A pass-through interceptor is free per streamed message** and about
  half a microsecond per unary call — so Contexts/interceptCall's attach is
  effectively per call, not a per-message tax; nothing to change on the
  library side.
- **Setting response headers adds about a fifth of a microsecond more** per
  call (the forwarding proxy and the metadata merge), invisible across a
  long stream. The +5–7% at the 40k unary step is the same absolute cost
  against a call that is idle most of the time, not a different cost.
Publishable form: "an interceptor costs ~0.5–0.7 µs per call; on a
streamed message it costs nothing measurable."
