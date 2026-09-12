import json,subprocess,sys,collections
f=sys.argv[1]
out=subprocess.run(['/opt/java/bin/jfr','print','--json','--events','jdk.ExecutionSample',f],capture_output=True,text=True).stdout
ev=json.loads(out)['recording']['events']
bythread=collections.Counter(); frames=collections.defaultdict(collections.Counter); leaf=collections.defaultdict(collections.Counter)
def cls(fr):
    m=fr['method']; return f"{m['type']['name']}.{m['name']}"
BUCKETS=[('write/flush',['Http2FrameWriter','Http2ConnectionEncoder','WriteQueue','AbstractChannel.write','flush','DefaultHttp2FrameWriter','NettyServerHandler.write','SendGrpcFrameCommand','NettyServerStream','ChannelOutboundBuffer','doWrite','writev']),
         ('read/decode',['Http2FrameReader','DefaultHttp2FrameReader','Http2ConnectionDecoder','Http2InboundFrameLogger','NettyServerHandler.onData','MessageDeframer','deframe','onDataRead','ByteToMessageDecoder','epollInReady','doReadBytes']),
         ('hand-off/execute',['SerializingExecutor','ForkJoinPool','VirtualThread','execute','JumpToApplicationThread','messagesAvailable','LockSupport.unpark','Unsafe.unpark','ThreadPerTaskExecutor']),
         ('flow-control/window',['FlowControl','windowUpdate','WindowUpdate','DefaultHttp2LocalFlowController','DefaultHttp2RemoteFlowController','returnProcessedBytes','consumeBytes']),
         ('epoll/syscall',['Native.epollWait','epollWait','Native.','EpollEventLoop.run','processReady','SocketDispatcher','FileDescriptor.']),
         ('protobuf/codec',['clj_protobuf','CodedInputStream','CodedOutputStream','acme.greeter','clojure.lang'])]
for e in ev:
    v=e['values']; t=v['sampledThread']['javaName']; st=[cls(fr) for fr in v['stackTrace']['frames']]
    bythread[t]+=1; leaf[t][st[0]]+=1
    b='other'
    for name,keys in BUCKETS:
        if any(any(k in fr for k in keys) for fr in st): b=name; break
    frames[t][b]+=1
groups=collections.defaultdict(collections.Counter); gtot=collections.Counter()
for t,c in bythread.items():
    g='loop' if 'epoll' in t else ('carrier' if 'ForkJoinPool' in t or 'VirtualThread' in t or t.startswith('') and 'ForkJoin' in t else t)
    if 'epoll' not in t and 'ForkJoin' not in t: g='other:'+t
    gtot[g]+=c
    for b,n in frames[t].items(): groups[g][b]+=n
total=sum(bythread.values()); print(f"samples {total}")
for g,c in gtot.most_common(6):
    print(f"== {g}: {c} samples ({100*c/total:.0f}%)")
    for b,n in groups[g].most_common(): print(f"   {100*n/c:5.1f}% {b}")
for t in [t for t in bythread if 'epoll' in t][:1]:
    print(f"-- top leaf frames on {t}")
    for fr,n in leaf[t].most_common(12): print(f"   {100*n/bythread[t]:5.1f}% {fr}")
