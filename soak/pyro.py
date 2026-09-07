#!/usr/bin/env python3
"""Attribution from a Pyroscope flamebearer, read through the API-server proxy: soak/pyro.py <service_name|flamebearer.json[.gz]> <from_epoch> <until_epoch>
Prints thread-level shares (root frames), layer shares by self time, and top self frames."""
import sys, json, subprocess, urllib.parse, re, collections
svc, frm, until = sys.argv[1], sys.argv[2], sys.argv[3]
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
# levels[i] is a flat list of (offsetDelta, total, self, nameIdx); offsets are deltas within a level.
LAYERS = [
    ("kernel", r"^\[k\]|^__|^entry_SYSCALL|^do_syscall|^ksys_|^sock_|^tcp_|^ip_|^net_|^__x64|^__arm64|^el0|^vfs_|^ep_|^schedule|^finish_task|^futex"),
    ("jit/gc (libjvm)", r"^libjvm\.so|^G1 |^GC Thread|^C[12] CompilerThre|^VM Thread|^VM Periodic|^Compile"),
    ("syscalls (libc: writev/read/epoll/futex)", r"^libc|^libpthread|^ld-linux|^\./lib/"),
    ("jvm dispatch stubs (itable/vtable)", r"stub$|^\.unknown|^\[unknown\]"),
    ("jetty", r"^org/eclipse/jetty"),
    ("pedestal/ring", r"^io/pedestal|^ring/"),
    ("json (jsonista/jackson)", r"^jsonista|^com/fasterxml"),
    ("clojure runtime", r"^clojure/"),
    ("netty", r"^io/netty"),
    ("grpc-java", r"^io/grpc"),
    ("protobuf", r"^com/google/protobuf"),
    ("app (clj_grpc/bench)", r"^clj_grpc|^acme/"),
    ("java std", r"^java/|^jdk/|^sun/"),
    ("async-profiler agent", r"^libasyncProfiler|^io/pyroscope|^one/profiler"),
]
def layer(n):
    for L, rx in LAYERS:
        if re.search(rx, n): return L
    return "other"
selfby = collections.Counter(); layerself = collections.Counter()
for lvl in levels:
    for i in range(0, len(lvl), 4):
        _, t, s, ni = lvl[i:i+4]
        if s: selfby[names[ni]] += s; layerself[layer(names[ni])] += s
roots = []
lvl = levels[1] if len(levels) > 1 else []
off = 0
for i in range(0, len(lvl), 4):
    d, t, s, ni = lvl[i:i+4]; off += d
    roots.append((t, names[ni]))
print(f"# {svc} {frm}-{until}: total samples {total}")
print("## threads (root frames) by total")
for t, n in sorted(roots, reverse=True)[:12]:
    print(f"  {100*t/total:5.1f}%  {n}")
print("## layers by self time")
for L, s in layerself.most_common():
    print(f"  {100*s/total:5.1f}%  {L}")
print("## top self frames")
for n, s in selfby.most_common(25):
    print(f"  {100*s/total:5.1f}%  {n[:110]}")
