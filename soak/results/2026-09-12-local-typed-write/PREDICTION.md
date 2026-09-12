# Pre-registered — the typed WRITE path for the compiled arm (protoc-gen-clojure branch 03c1d8c on clj-protobuf 0.4.0), local x86, 2026-09-12

One-variable pair: `w0.jar` = clj-grpc deps/clj-protobuf-0.4.0 (0.7.0 reads,
codec/set-field! writes) vs `w1.jar` = the same plus the fixture regenerated
by the branch plugin (codec/slot-set! per field behind the compiled guard).
Held on clj-protobuf's byte_identity proof for the branch fixture. Same
pinned harness, chart defaults, VT and `:direct`, one and eight connections,
streaming, realistic tier, 4 cores; CPU per message from the cgroup
counters.
- T1 `:direct` one connection: −8 to −15% CPU per message (the write side's
  generic per-field dispatch, keyword lookups and derefs were the largest
  owned block; interop's typed writes were −22–30% on encode alone).
- T2 VT one connection: −6 to −12%.
- T3 eight-connection shapes: the read path's win died there (0–3%); if the
  write path also lands ≤ 3% on eight connections, that is information, as
  the plugin session put it: two per-field optimisations in a row failing
  to move real load says the per-field layer is not where streaming cost
  lives on those shapes, and effort redirects rather than funds a third.
Nil below 3%.

## clj-protobuf's prediction on record (19:10, before any run)
Smaller than the read path's −6 to −9% at one connection: the write side's
per-field cost is split between codec/set-field! dispatch (removed) and the
coercion plus the field write (kept, behind a symbol). Low single digits per
streamed message at one connection, nil at eight. Byte identity, equivalence
(incl. groups through slot-set!), contract, interop and wire all green on
their side against the branch fixture; embedded descriptors byte-identical
to the 0.7.0 fixtures, so the write path is the only variable.
