import json,re,base64,sys
# precise CPU per delivered message from the Job log: cgroup usage delta / stream_messages_received
def rows(path):
    txt=open(path).read(); out=[]
    for hdr,blk in re.findall(r'#NH-STEP (\{.*?\})\n(.*?)#NH-END', txt, re.S):
        h=json.loads(hdr)
        if h.get('warmup'): continue
        def cpu(b64):
            for l in base64.b64decode(b64).decode().splitlines():
                if l.startswith('cgroup_cpu_usage_seconds_total '): return float(l.split()[1])
        try:
            j=json.loads(blk); g=[r for r in j['results'] if r['name']=='global'][0]
            c={x['name']:float(x['value']) for x in g['counters']}
            ok=c.get('benchmark.stream_messages_received') or c.get('benchmark.http_2xx') or c.get('benchmark.stream_messages_sent')
            dur=h['end']-h['start']
            out.append((h['rps'], ok/dur, (cpu(h['metrics_after'])-cpu(h['metrics_before']))*1e6/ok))
        except Exception as e: out.append((h['rps'],None,None))
    return out
W=sys.argv[1]
for spec in sys.argv[2:]:
    if ':' not in spec:
        for o,d,c in rows(f'{W}/{spec}.log'):
            if c: print(f'{spec} {o:>8} {d:>9,.0f} {c:6.2f} µs/msg')
        continue
    a,b=spec.split(':')
    ra,rb=rows(f'{W}/{a}.log'),rows(f'{W}/{b}.log')
    print(f"== {a} vs {b}   offered  delivered a/b   µs/msg a/b   Δ")
    for (o,d1,c1),(o2,d2,c2) in zip(ra,rb):
        if c1 and c2: print(f"{o:>8} {d1:>9,.0f}/{d2:<9,.0f} {c1:6.2f}/{c2:<6.2f} {100*(c2/c1-1):+.1f}%")
