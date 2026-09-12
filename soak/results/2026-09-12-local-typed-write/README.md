# The typed WRITE path for the compiled arm — local x86, 2026-09-12

Plan item: the compiled arm's `X->proto` was `.newBuilderForType` plus a
generic `codec/set-field!` per field — `proto-value` → `map->message`, with
keyword-hash map lookups and derefs — the largest owned block in the
loop-breakdown JFR (Clojure runtime 26% of carrier samples, most of it
there). protoc-gen-clojure branch `compiled-arm-typed-write` (03c1d8c, draft
PR #60) emits `(codec/slot-set! b <index> (:key m))` per field behind a
compiled-prototype guard, on clj-protobuf 0.4.0's `codec/slot-set!`, which
coerces per kind, elides implicit-presence defaults, clears oneof siblings
and ignores nil — the coercion behind a runtime symbol rather than baked
into generated files, by design. clj-protobuf's suite is green on the
branch fixture (byte_identity, equivalence including groups, contract,
interop, wire); the embedded descriptors are byte-identical to the 0.7.0
fixtures, so the write path is the only variable.

One-variable pair: `w0.jar` = clj-grpc on clj-protobuf 0.4.0 with the
committed fixture (typed reads, codec writes) vs `w1.jar` = the same with the
branch fixture (`harness/typed-write-fixture.patch`). Same pinned harness,
chart defaults, streaming, realistic tier, 4 cores; `PREDICTION.md` first,
with clj-protobuf's own prediction (low single digits at one connection,
nil at eight) on record.

## Result (cgroup-precise µs per message, w0 → w1)

== w0-direct-1conn vs w1-direct-1conn   offered  delivered a/b   µs/msg a/b   Δ
  160000   143,561/137,493     7.28/7.65   +5.0%
  240000   146,984/139,007     6.73/7.24   +7.5%
  320000   144,075/141,688     6.85/6.95   +1.3%
  400000   143,530/144,611     6.88/6.82   -0.8%
== w0-vt-1conn vs w1-vt-1conn   offered  delivered a/b   µs/msg a/b   Δ
  160000   156,898/157,023    16.12/16.18  +0.4%
  240000   239,923/239,752    12.49/12.51  +0.2%
  320000   310,710/316,656    10.34/10.26  -0.8%
  400000   384,832/384,899     8.92/8.95   +0.4%
== w0-vt-8conn vs w1-vt-8conn   offered  delivered a/b   µs/msg a/b   Δ
  160000   157,204/157,119    21.21/21.28  +0.3%
  240000   236,005/236,065    15.67/15.68  +0.1%
  320000   314,736/314,753    11.73/11.71  -0.2%
  400000   391,151/391,561     9.46/9.47   +0.1%
== w0-direct-8conn vs w1-direct-8conn   offered  delivered a/b   µs/msg a/b   Δ
  160000   156,514/156,797    18.86/18.97  +0.6%
  240000   236,066/236,063    14.52/14.53  +0.1%
  320000   314,733/309,676    11.81/11.87  +0.5%
  400000   393,295/393,377     9.82/9.81   -0.1%

**Nil on every shape at the knee** (within ±1%), and up to +7% per message
below the knee on `:direct` one connection. No errors. The pre-registered
T1/T2 (−6 to −15%) failed; clj-protobuf's "low single digits at one
connection" was itself an over-estimate. The branch does not merge and the
plugin's draft PR should not be released for this; the 0.4.0 runtime symbol
is harmless and stays on Clojars.

Reading: the write side's per-field cost was already dominated by the
coercion and the field write, which the emission could only move behind a
symbol, not remove; the dispatch it did remove was not what the carrier
was paying for. With the read path's 4–9% banked and both the write path
and the marshaller at nil, the per-field layer is not where streaming cost
lives on this stack.
