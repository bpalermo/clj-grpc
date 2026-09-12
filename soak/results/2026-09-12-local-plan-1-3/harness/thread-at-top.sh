#!/usr/bin/env bash
# thread-at-top.sh LABEL — wait for the run's top step (start + 90 s warmup + 3×60 s) and
# read the server JVM's per-thread CPU over 20 s from /proc.
label="$1"; W="$(cd "$(dirname "$0")" && pwd)"; RES="$W/results4"
until grep -q "#NH-RUN $label start=" "$RES/steps.log" 2>/dev/null; do sleep 5; done
start=$(grep "#NH-RUN $label start=" "$RES/steps.log" | tail -1 | sed 's/.*start=//')
while [ $(( $(date +%s) - start )) -lt 300 ]; do sleep 5; done
pid=$(docker inspect -f '{{.State.Pid}}' srv 2>/dev/null) || exit 1
python3 - "$pid" "$label" <<'PY' >> "$RES/threads-at-top.txt"
import os,time,sys
pid,label=sys.argv[1],sys.argv[2]
def snap():
    d={}
    for t in os.listdir(f'/proc/{pid}/task'):
        try:
            s=open(f'/proc/{pid}/task/{t}/stat').read(); name=s[s.index('(')+1:s.rindex(')')]
            f=s[s.rindex(')')+2:].split(); d[t]=(int(f[11])+int(f[12]),name)
        except Exception: pass
    return d
a=snap(); time.sleep(20); b=snap()
rows=sorted([(100*(b[t][0]-a[t][0])/2000, b[t][1]) for t in b if t in a], reverse=True)
print(f"## {label} at {time.strftime('%H:%M:%S')} total {sum(r[0] for r in rows)/100:.2f} cores")
for r in rows[:8]: print('%6.1f%% %s'%r)
PY
