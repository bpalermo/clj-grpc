#!/usr/bin/env bash
# usersys-at-top.sh LABEL — at the run's top step, per-thread USER vs KERNEL CPU over 20 s
label="$1"; W="$(cd "$(dirname "$0")" && pwd)"; RES="$W/results6"
until grep -q "#NH-RUN $label start=" "$RES/steps.log" 2>/dev/null; do sleep 5; done
start=$(grep "#NH-RUN $label start=" "$RES/steps.log" | tail -1 | sed 's/.*start=//')
while [ $(( $(date +%s) - start )) -lt 300 ]; do sleep 5; done
pid=$(docker inspect -f '{{.State.Pid}}' srv 2>/dev/null) || exit 1
python3 - "$pid" "$label" <<'PY' >> "$RES/usersys-at-top.txt"
import os,time,sys
pid,label=sys.argv[1],sys.argv[2]
def snap():
    d={}
    for t in os.listdir(f'/proc/{pid}/task'):
        try:
            s=open(f'/proc/{pid}/task/{t}/stat').read(); name=s[s.index('(')+1:s.rindex(')')]
            f=s[s.rindex(')')+2:].split(); d[t]=(int(f[11]),int(f[12]),name)
        except Exception: pass
    return d
a=snap(); time.sleep(20); b=snap()
rows=[]
for t in b:
    if t in a:
        u=(b[t][0]-a[t][0])/20; s=(b[t][1]-a[t][1])/20; rows.append((u+s,u,s,b[t][2]))
rows.sort(reverse=True)
print(f"## {label} at {time.strftime('%H:%M:%S')} (fractions of a core: total user sys)")
for r in rows[:8]: print('%5.2f  %5.2f %5.2f  %s'%r)
PY
