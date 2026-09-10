# Ladder recheck and version bracket — 2026-09-10

Nine unary/REST/stream runs at 1 CPU, every one with node CPU sampled
(`soak/node-cpu.sh`) and driver CPU sampled (`soak/client-cpu.sh`). Conclusions
are in [`../../soak-results.md`](../../soak-results.md); this is the backing
data. Predictions and thresholds were fixed before the runs in
[`PREDICTION.md`](PREDICTION.md).

## Why

Every 2-CPU measurement in this campaign turned out to have been taken on a
saturated node. The 1-CPU ladder — which every other conclusion cites — was
measured before the node instrument existed, so nothing confirmed it had
headroom.

## What the runs are

| directory | chart | what it measures |
|---|---|---|
| `rest-h1`, `rest-h2c` | 0.2.21 | rungs 0 and 1, against the published figures |
| `unary`, `stream` | 0.2.21 | rungs 2 and 3 |
| `ceiling` | 0.2.21 | unary pushed to 10,000 offered to find its actual knee |
| `nolink` | 0.2.21 | direct linking off — prices the lever on the current stack |
| `proto22` | 0.2.19 | clj-protobuf 0.2.2, linking on — the codec version A/B |
| `pub28` | 0.2.8 | the stack the previous ladder was published from |
| `pub29` | 0.2.9 | +protobuf-java 4.36.1 |

## What they found

**The 1-CPU ladder is not host-limited.** Node CPU peaked at 3.37 of 4 across
every rung, against the 4.11 that invalidated the 2-CPU work.

**Both gRPC rungs were understated**, for two independent reasons. The published
ramps stopped at the knee (1.2% and 0.2% shedding) rather than past it — the
same chart with a longer ramp gives ~15% more — and the stack has since become
~9% cheaper per request.

**The version bracket** attributes that ~9%: direct linking ~4.9%, protobuf-java
4.36.1 ~2.9%, everything else ~1.8%, and clj-protobuf 0.2.2 → 0.2.5 nil.

**Run-to-run and ramp-shape spread is ~15%**, which is wider than most
differences this campaign has reasoned from. `unary` and `ceiling` put the same
arm at 5,999 and 5,240 rps at the same 6,000 offered; the difference is that one
climbed to it and the other started there.

## One discarded run

A first attempt at `nolink` measured nothing: `LADDER_EXTRA_SET` passes through
`helm --set-string`, so `directLinking.enabled=false` set the *string* `"false"`,
which Helm's `and` treats as true. The flag stayed on and the run would have
compared the lever against itself. Caught by reading `JAVA_TOOL_OPTIONS` on the
pod rather than trusting the values; it produced no log and nothing entered these
results. An empty value is falsy and is what the re-run used.
