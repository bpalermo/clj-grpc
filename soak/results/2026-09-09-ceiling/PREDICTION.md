# Prediction, recorded 2026-09-09 16:2x UTC — before the runs completed

Written before seeing either result, at the clj-protobuf session's prompting:
"decide BEFORE the run rather than after, because it is the direction that
will tempt a rationalisation."

## The test

Interop arm (no clj-protobuf monitor on its path), 2 CPU, chart 0.2.19, at 4
and 8 connections, against its own 25,287 msg/s at 1.47 cores with 2
connections.

## What I expect

**Flat: ~25,000 msg/s at 4 and 8 connections, within about 5%**, with cores
consumed RISING to roughly 1.6-1.7 as more event loops coordinate — the same
shape the compiled arm showed (22,866 / 22,591 / 23,133 at 1.49 / 1.69 / 1.67
cores).

Reasoning: the compiled arm was already flat across 2/4/8, and if that flatness
were caused by its monitor then interop should climb. But interop is only ~10%
above compiled at two connections, which is a smaller gap than a
fully-serialized encode path would leave, so I do not think the monitor is what
flattened compiled's curve.

## What each outcome licenses

**Flat (predicted).** A cap invariant to connection count, on an arm with no
monitor, leaving half a core idle. The compiled arm's flatness is then also
connection-invariant and the monitor merely lowered the level. The cause is
upstream of the codec: transport, driver, or pod.

**Climbs.** Compiled's flatness across 2/4/8 plausibly WAS the monitor, and
interop's real ceiling is higher and connection-dependent.

The asymmetry matters, and it is the peer's point transposed from cores to
connections: **flat is the clean confirmation; climbing is the ambiguous
result.** Climbing rules out an ABSOLUTE cap, not a serialization point — a
lock with a short enough duty cycle still permits partial scaling. So a climb
must not be written up as "not a serialization point"; only as "not an
absolute one".

Threshold set in advance: under 5% change reads as flat, over 10% as a climb,
5-10% is indeterminate and needs a third connection count.

## Amendment, 19:21 UTC — still before any result

Added at the clj-protobuf session's prompting, with zero runs complete and no
result opened (`ladder complete` count was 0; only the in-progress conn4
directory existed). Recorded as an amendment rather than an edit so the
original stands.

**Flat is not automatically clean either, and the discriminator is the CORES
column, not msg/s:**

- flat throughput, cores stuck at 1.5–1.7 with real idle capacity
  → serialization point. Nothing is using the headroom that exists.
- flat throughput, cores climbing toward 1.9–2.0
  → plain CPU saturation at two cores. The arm is out of machine and there is
  no mystery to chase.

My recorded prediction (flat, cores 1.6–1.7) had already implicitly picked the
first without saying so, which is exactly how a flat number that is *not* a
confirmation gets written up as one.

**Threshold on cores, fixed in advance: above 1.85 reads as saturation
regardless of what throughput did.**

## Why connections are a weaker instrument than cores, and why they still work

Varying cores changes how much parallelism *can* exist; varying connections at
fixed cores does not, so on its own it would be the weaker test. What rescues
it here is the half-idle core: with spare CPU available, a *per-connection*
bound would let throughput climb as connections are added, and a *global*
serialization point would not. So this run discriminates along "global vs
per-connection" rather than "contended vs not".

## What this run cannot answer

If interop comes back flat, the honest conclusion is that both arms share a cap
upstream of the codec — and that says **nothing either way** about how much the
clj-protobuf monitor was worth. The monitor's value is measured by the
after-run on the *compiled* arm against 0.2.5, which has not happened. Two
separate questions; this run answers only the first.
