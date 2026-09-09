# After-run design and interpretive limits — recorded 2026-09-09, before the
# chart carrying clj-protobuf 0.2.5 exists

The before-number (merged as ac73f72) is explicitly framed as measured against
a known defect, with a prediction on record: once clj-protobuf 0.2.5 is in, the
interop-over-compiled gap should shrink toward the 1-4% the 1-CPU runs showed.
This file fixes what the after-run can and cannot conclude, written while the
chart is still a pending PR (clj-grpc #79, chart 0.2.21) and no number exists.

## The run

Exactly the pair from `soak/results/2026-09-09-lock-ab/`: compiled and interop,
both pinned to `:direct`, 2 CPU, worker-02, two connections (40 streams,
mcs=20), ramp 20,000-36,000, paired in one session, on the single chart version
that carries 0.2.5 on both arms. ~35 minutes.

Before-numbers to beat: compiled 23,051 msg/s at 0.065 ms/msg; interop 25,287
at 0.058. Gap: +9.7% throughput, -10.8% CPU per message.

## The limit that must not be elided

**Two cores may be too few to show this fix, and a small narrowing is therefore
not a refutation.** Upstream's win is a SCALING win, measured 1 -> 8 threads
(encode 0.99x -> 8.48x, `.build` 0.31x -> 2.11x). This arm has two event loops
on two cores, so it exercises the shallowest end of that curve. The one part of
upstream's result that should show at any thread count is the +23% single-thread
gain from no longer taking an uncontended monitor.

So:

- **Gap closes to 1-4%** -> prediction confirmed, monitor was the whole of the
  two-core difference.
- **Gap narrows but stays above ~5%** -> ambiguous, and must be written as
  ambiguous. Two cores may simply be too few for the removal to show its value,
  OR something other than the monitor contributes. This run cannot separate
  those, and the honest write-up says so rather than picking one.
- **Gap does not narrow at all** -> the strongest available evidence that the
  two-core difference was not the monitor. Still not proof, since the arms
  differ in more than the monitor.

**A result in the middle band is the likely one and is not a refutation of
clj-protobuf's fix.** Their 1 -> 8 thread measurement stands on its own bench;
nothing measurable on a two-core pod can overturn it, and this doc must not be
cited as if it could.

## What would actually test the scaling claim

More cores than this cluster can currently give a single arm: no node has 3
cores free, and worker-02's remainder belongs to aether. Until that changes,
the scaling half of upstream's result is not testable here, and the after-run
is a check on the shallow end only.

Recorded before the chart existed, at the clj-grpc session's suggestion, for
the same reason as the ceiling run's prediction file: the direction that tempts
a rationalisation is the one to fix in advance.
