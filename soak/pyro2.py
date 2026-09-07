#!/usr/bin/env python3
"""Inclusive attribution: pyro2.py <service|flamebearer.json[.gz]> <from> <until> [--path REGEX]
Prints inclusive (subtree) shares for library patterns (no double counting on recursion)
and, with --path, the most common ancestor chains of frames matching REGEX."""
import sys, json, subprocess, urllib.parse, re, collections
svc, frm, until = sys.argv[1:4]
path_rx = re.compile(sys.argv[5]) if len(sys.argv) > 5 and sys.argv[4] == '--path' else None
def load_flamebearer(svc, frm, until):
    """Live from Pyroscope through the API-server proxy, or offline when svc is a
    saved flamebearer file (.json or .json.gz)."""
    import os, gzip
    if os.path.exists(svc):
        opener = gzip.open if svc.endswith(".gz") else open
        with opener(svc, "rt") as f: return json.load(f)["flamebearer"]
    q = urllib.parse.quote('process_cpu:cpu:nanoseconds:cpu:nanoseconds{service_name="%s"}' % svc)
    url = f"/api/v1/namespaces/o11y/services/pyroscope:4040/proxy/pyroscope/render?query={q}&from={frm}&until={until}&format=json"
    return json.loads(subprocess.check_output(["kubectl","--context","talos-main","get","--raw",url]))["flamebearer"]
fb = load_flamebearer(svc, frm, until)
names, levels, total = fb["names"], fb["levels"], fb["numTicks"]
# Build nodes: (level, start, end, total, self, name)
nodes = []
for li, lvl in enumerate(levels):
    off = 0; row = []
    for i in range(0, len(lvl), 4):
        d, t, s, ni = lvl[i:i+4]; off += d
        row.append((off, off + t, t, s, names[ni])); off += t
    nodes.append(row)
PATTERNS = [
  ("clj_protobuf codec (all)", r"^clj_protobuf/"),
  ("  codec/proto-value", r"^clj_protobuf/codec\$proto_value"),
  ("  codec/get-field", r"^clj_protobuf/codec\$get_field"),
  ("  codec/message->map", r"^clj_protobuf/codec\$message__GT_map"),
  ("  codec/set-field! / ->proto", r"^clj_protobuf/codec\$(set_field|.*__GT_proto)"),
  ("clj_grpc (server/impl)", r"^clj_grpc/"),
  ("acme.greeter generated", r"^acme/"),
  ("io/grpc (all)", r"^io/grpc/"),
  ("  io/grpc/netty", r"^io/grpc/netty"),
  ("  io/grpc/internal", r"^io/grpc/internal"),
  ("io/netty (all, incl. under grpc)", r"^io/netty/"),
  ("com/google/protobuf (all)", r"^com/google/protobuf/"),
  ("  DynamicMessage / FieldSet / SmallSortedMap", r"^com/google/protobuf/(DynamicMessage|FieldSet|SmallSortedMap)"),
  ("  Descriptors\\$", r"^com/google/protobuf/Descriptors\$"),
  ("  CodedInput/OutputStream", r"^com/google/protobuf/Coded(Input|Output)Stream"),
  ("  Descriptor.toProto", r"^com/google/protobuf/Descriptors\$Descriptor\.toProto"),
  ("Throwable.fill_in_stack_trace", r"fill_in_stack_trace|Throwable\.<init>|fillInStackTrace"),
  ("java.util.concurrent (VT/ForkJoin/locks)", r"^java/util/concurrent/"),
  ("libjvm (JIT+GC+runtime)", r"^libjvm"),
]
print(f"# {svc} {frm}-{until}: total {total}")
print("## inclusive shares (subtree, first match on a path counts)")
for label, rx in PATTERNS:
    r = re.compile(rx); claimed = []; acc = 0
    for li, row in enumerate(nodes):
        for (a, b, t, s, n) in row:
            if r.search(n) and not any(ca <= a and b <= cb for (ca, cb) in claimed):
                acc += t; claimed.append((a, b))
    if acc: print(f"  {100*acc/total:5.1f}%  {label}")
if path_rx:
    print(f"## ancestor chains for /{path_rx.pattern}/ (top 6)")
    chains = collections.Counter()
    for li, row in enumerate(nodes):
        for (a, b, t, s, n) in row:
            if path_rx.search(n):
                chain = []
                for pl in range(li - 1, 0, -1):
                    par = next((pn for (pa, pb, pt, ps, pn) in nodes[pl] if pa <= a and b <= pb), None)
                    if par: chain.append(par)
                    if len(chain) >= 14: break
                key = " <- ".join(x[:60] for x in chain[:14])
                chains[key] += t
    for k, t in chains.most_common(6):
        print(f"  {100*t/total:5.1f}%  {k}")
