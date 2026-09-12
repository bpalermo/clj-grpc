# Pre-registered — VT streaming levers, local x86, 2026-09-12

VT, 4 server cores (cpuset 2–5), streaming, 40 streams, one connection (one
worker) and eight connections (MCS 5, four workers), ramp 160k–400k, same
harness. Baseline re-run in-session (JDK 21 distroless, defaults) so every
lever is a same-session pair; the previous baseline was 237k / 163k.

- E1 JDK 25 runtime (eclipse-temurin:25-jre, same jar): ≥ +10% on both
  shapes from scheduler/continuation work since 21; nil if < 4%.
- E2 `-Dio.netty.eventLoopThreads=2` (loops are I/O-only under VT; the
  default 8 loops on 4 cores compete with 4 carriers): 8-conn ≥ +15%;
  1-conn unchanged (±4%).
- E3 `-XX:+UseParallelGC` (VM Thread was 15% of a core at the plateau): +3–8%.
- E4 REQUEST_BATCH=32 (removes the per-message vthread→loop request(1)
  hop): 1-conn ≥ +10% (the loop was the ceiling at 82%); 8-conn ≥ +5%.
Fail thresholds: below +4% = nil (replicate floor 3–4%).

## Phase C (added 22:41 after E3 landed at +25%, before any phase C run)
- C1 G1 with `-Xmn256m` (half the heap young): if the ParallelGC gain is
  young-gen sizing, ≥ +15%; if it is G1's barriers/concurrent work, < +5%.
- C2 generational ZGC (`-XX:+UseZGC -XX:+ZGenerational`): predicted between
  G1 and Parallel.
- C3 ParallelGC on `:direct`, 8 connections, 4 cores (baseline ~278k today):
  if the 2.8-core plateau was GC, ≥ +10%; else nil.
- C4 ParallelGC + `-Xmn256m` on VT: ≥ +5% over Parallel alone if sizing still
  matters.

## Phase D — combinations (added 22:47 after E3 +25% and E4 +27%, before any phase D run)
- D1 ParallelGC + REQUEST_BATCH=32, one connection: the two attack
  different costs (GC vs the per-message loop hop), so ≥ +40% over baseline
  (≥ 335k); if they overlap, ≤ +30%.
- D2 the same on eight connections; D3 adds two event loops — read against
  phase A's eight-connection rows.
- D4 D1 plus CALL_EXECUTOR=percall: additive only if phase B shows percall
  positive on its own.

## Correction (22:59, before phase C ran): the baseline GC is Serial, not G1
`-XX:+PrintFlagsFinal` in the same container shape (1 GB limit) shows JDK 21
and JDK 25 both pick SerialGC — under 2 GB the JVM is not "server-class".
So E3 (+25%) is ParallelGC against Serial, the cluster's 1-CPU arms also run
Serial, and phase C is re-cut to separate collector from young-gen size:
- C1 Serial + `-Xmn256m`: if E3 was young-gen sizing, ≥ +15%.
- C1b G1 (`-XX:+UseG1GC`): the collector the doc assumed; predicted below
  Parallel, above Serial.
- C2 generational ZGC; C3 `:direct` + ParallelGC; C4 Parallel + `-Xmn256m`
  unchanged.

## Phase E (added 00:11 after C3 delivered all 400k, before any phase E run)
- E5 `:direct` + ParallelGC, eight connections, ramp 400k–600k: the true
  4-core ceiling; predicted 420–480k at ~0.010 ms with CPUs at ~99%.
- E6 `:direct` + Serial + `-Xmn256m`, same shape and ramp: within ±5% of E5
  if the young-generation reading holds for `:direct` too.

## Phase F (added 00:16 after D1 delivered all 400k, before any phase F run)
- F1 VT + ParallelGC + REQUEST_BATCH=32, one connection, ramp 400k–640k:
  the ceiling; predicted 440–520k, limited by the connection's loop again.

## Phase G (added 00:28 after D3 delivered all 400k, before any phase G run)
- G1 VT + ParallelGC + REQUEST_BATCH=32 + 2 event loops, eight connections,
  ramp 400k–640k: predicted 440–520k, within ±10% of the one-connection
  combination's ceiling (F1) — if higher, connections stop costing VT once
  the loops and GC are fixed.
