# Pre-registered — clj-grpc's direct marshaller, local x86, 2026-09-12

One jar (`marsh.jar` = main + the marshaller behind `-Dclj-grpc.marshaller=direct`),
one flag. Same pinned harness, chart defaults, typed-slot fixture, streaming,
40 streams, realistic tier, 4 server cores. Shapes: VT one connection, VT
eight connections with two loops, `:direct` one connection. Off/on
interleaved per shape; CPU per message from the cgroup counters.
- M1 the per-message byte[] copy (~2% of carrier samples) and the framer's
  per-field OutputStream writes (MessageFramer$OutputStreamAdapter.write
  3.5% + CodedOutputStream writeLazy 2.7%) go away: −3 to −6% CPU per
  message on the VT shapes; on `:direct` the same or slightly less.
- M2 capacity on the VT one-connection shape within ±4% (the loop is the
  ceiling and none of this is on the loop); eight connections +0–5%.
- M3 if the zero-copy parse does not engage (the stream is not HasByteBuffer
  on this transport) the parse side is nil and only the write side shows;
  the run notes which path was taken.
Nil below 3% (one jar, one flag, same session).

## Note (18:55): first attempt was wrong under load, rows discarded
The first marshaller collected ByteBuffers across `skip`; grpc's composite
buffer releases a consumed chunk, so under load the collected buffers were
reused memory: 21/40 streams failed with INTERNAL "Invalid protobuf byte
sequence" and −30% throughput (kept under `broken/`). The e2e suite could
not catch it (one chunk, no memory pressure). Fixed to parse zero-copy only
when a single chunk holds the whole message, before advancing the stream;
otherwise buffered from the stream. Pair re-run from scratch.
