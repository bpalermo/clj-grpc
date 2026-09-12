# clj-grpc's own marshaller — local x86, 2026-09-12

Code we own on the carrier path: grpc-java's `ProtoUtils/marshaller` copies
each inbound message into a ThreadLocal byte[] before decoding, and under a
thread-per-task virtual-thread executor that ThreadLocal never hits, so the
copy is paid per message (`Arrays.copyOfRangeByte`, ~2% of carrier samples
in the loop-breakdown JFR). The branch `feat/direct-marshaller`
(`harness/direct-marshaller.patch`) decodes from the transport's own
ByteBuffer through `io.grpc.HasByteBuffer` when one chunk holds the whole
message and serializes once to a byte[] handed to the framer as a
`KnownLength` + `Drainable` stream, behind `-Dclj-grpc.marshaller=direct`;
e2e and service_codec variants run the suite on it. Same pinned harness,
chart defaults, typed-slot fixture, one jar, one flag, interleaved.

**The first attempt was wrong under load** (`broken/`): it collected
ByteBuffers across `skip`, and grpc's composite buffer releases a consumed
chunk, so under load the buffers were reused memory — 21/40 streams ended
INTERNAL "Invalid protobuf byte sequence", −30% throughput. The e2e suite
could not see it (one chunk, no memory pressure). Fixed to the one-chunk
case, parsed before the stream advances.

## Result (cgroup-precise µs per message, off → on)

== off-vt-1conn vs on-vt-1conn   offered  delivered a/b   µs/msg a/b   Δ
  160000   156,948/156,605    16.35/16.09  -1.6%
  240000   239,884/229,889    12.62/12.46  -1.3%
  320000   294,948/293,318    10.76/10.74  -0.3%
  400000   378,607/338,150     9.11/9.42   +3.5%
== off-vt-8conn vs on-vt-8conn   offered  delivered a/b   µs/msg a/b   Δ
  160000   154,103/156,993    20.66/20.92  +1.3%
  240000   236,064/236,066    15.52/15.56  +0.2%
  320000   302,752/314,678    12.10/11.76  -2.9%
  400000   367,540/392,466     9.95/9.43   -5.2%
== off-direct-1conn vs on-direct-1conn   offered  delivered a/b   µs/msg a/b   Δ
  160000   139,179/137,027     7.50/7.59   +1.1%
  240000   145,099/146,665     6.94/6.76   -2.6%
  320000   147,278/141,128     6.81/7.00   +2.8%
  400000   144,513/144,772     6.82/6.91   +1.3%

Nil on one connection under both executors (±3%), −5% per message and +7%
delivered at the top step on virtual threads with eight connections, no
errors. Shape-dependent 0–5%: below the pre-registered floor on two of
three shapes. **Not a lever**; the branch is kept as evidence and the
option is not merged. The pre-registered −3 to −6% assumed the framer's
per-field OutputStream writes were the cost; the one-write path trades
them for a 1 KB array per response and comes out even.
