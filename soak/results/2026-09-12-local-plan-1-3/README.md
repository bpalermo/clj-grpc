# Plan items 1–3 on the shipped defaults — local x86, 2026-09-12

`:worker-threads`, `:initial-flow-control-window` (clj-grpc #99) and the
virtual-thread scheduler's parallelism, each against a same-session baseline
that carries chart 0.2.23's defaults (`-Xmn256m`, `:inbound-credits 8`).
Same pinned harness (server cores 2–5, driver 6–9, loopback); VT; streaming,
40 streams, one connection (one worker) and eight (`--max-concurrent-streams
5`, four workers); ramp 160k–400k. `PREDICTION.md` first, with two recorded
perturbations: the first baselines overlapped a test run on this host and a
peer session's benchmark overlapped the window rows — both re-run clean
(`base2-*`, `win*2-*`), and only those are read. `threads-at-top.txt` is the
server JVM's per-thread CPU over 20 s at the top step, from `/proc`.

## Read against the clean baselines

| row | one connection (base2 ~387k at 0.009 ms) | eight connections (base2 ~301k at 0.013) |
|---|---|---|
| `:worker-threads 1` | — | ~336k at 0.010, **+12%** |
| `:worker-threads 2` | (nil, measured twice before) | ~332k at 0.011, **+10%** |
| `:worker-threads 4` | — | ~320k at 0.012, +6% |
| `:initial-flow-control-window` 4 MiB | ~387k, nil (p99 at the top step 49 → 28 ms) | — |
| `:initial-flow-control-window` 16 MiB | ~386k, nil; loop thread 97.5% vs 95% | ~388k at 0.010 with two loops, vs ~332k with two loops alone: +17% |
| scheduler parallelism 3 (one connection) / 2 (eight, two loops) | ~390k, nil | ~296k vs 332k with default carriers, **−11%** |

Per-thread at the top step, one connection, shipped defaults: the
connection's event loop **95–97%**, the four carriers 65%, the VM thread
1.4%. GC is gone from the picture and the single connection's loop is the
ceiling outright; nothing in items 2–3 moves it.

- Item 1 holds at +10–12% for a many-connection virtual-thread server, one
  loop as good as two, degrading monotonically toward Netty's default of
  2 × cores. Recommendation: `:worker-threads` 1–2 (about cores/4) under
  virtual threads; leave the default for `:direct`.
- Item 2 is nil on one connection at 4 and 16 MiB (tails improve, loop
  share unchanged at 97%) and **+17% on eight connections** at 16 MiB with
  two loops (~388k vs ~332k), one run: with several connections each starts
  with its own window and BDP has less traffic per connection to grow it
  from. Worth a replicate before a recommendation; ships as an option.
- Item 3 is nil on one connection and harmful on eight; closed.

## Every run — peak, cost, memory, against the CLEAN baselines (`base2-*`)

| run | peak msg/s | ms/msg | p99 ms at peak | RSS MB | vs base2 |
|---|---|---|---|---|---|
| base-1conn (perturbed) | 343,855 | 0.010 | 60.2 | 448 | -11% |
| base-8conn (perturbed) | 270,974 | 0.014 | 76.0 | 488 | -10% |
| loops2-8conn | 331,918 | 0.011 | 55.8 | 445 | +10% |
| loops1-8conn | 335,918 | 0.010 | 52.2 | 408 | +11% |
| loops4-8conn | 319,506 | 0.012 | 62.7 | 448 | +6% |
| win4m-1conn (perturbed) | 379,028 | 0.009 | 33.2 | 456 | -2% |
| win16m-1conn (perturbed) | 376,111 | 0.009 | 40.4 | 434 | -3% |
| win16m-loops2-8conn (perturbed) | 354,865 | 0.011 | 55.4 | 465 | +18% |
| par3-1conn | 389,507 | 0.009 | 43.1 | 428 | +1% |
| par2-loops2-8conn | 296,077 | 0.012 | 65.7 | 456 | -2% |
| base2-1conn | 386,592 | 0.009 | 48.5 | 414 | +0% |
| base2-8conn | 301,341 | 0.013 | 61.6 | 480 | +0% |
| win4m2-1conn | 386,669 | 0.009 | 27.7 | 465 | +0% |
| win16m2-1conn | 386,349 | 0.009 | 35.6 | 476 | -0% |
| win16m2-loops2-8conn | 387,766 | 0.010 | 36.5 | 462 | +29% |
