### local:off-vt-1conn (image=java21-debian12@sha256:f34fd3e4e2d7a246d764d0614f5e6ffb3a735930723fac4cfc25a72798950262; jar=marsh.jar; env='-e INBOUND_CREDITS=8 -e EXECUTOR=virtual '; jvm='-Xmn256m'; mcs=512; workers=1; VT, 4 cores)
| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB | conns |
|---|---|---|---|---|---|---|---|---|---|---|
| 2000 (warmup) | 1999.8 | 0.12 | 1.55 | 19.86 | 0.0 | 0.147 | 0.0 | 34 | 388 | 1 |
| 160000 | 158337.8 | 0.20 | 85.06 | 107.74 | 1651.9 | 0.016 | 0.0 | 211 | 414 | 1 |
| 240000 | 239705.4 | 0.22 | 15.06 | 45.73 | 285.7 | 0.012 | 0.0 | 211 | 421 | 1 |
| 320000 | 312229.1 | 0.54 | 29.06 | 48.79 | 7766.5 | 0.010 | 0.0 | 97 | 433 | 1 |
| 400000 | 362225.9 | 3.20 | 47.34 | 118.26 | 37726.1 | 0.009 | 0.0 | 152 | 441 | 1 |

### local:on-vt-1conn (image=java21-debian12@sha256:f34fd3e4e2d7a246d764d0614f5e6ffb3a735930723fac4cfc25a72798950262; jar=marsh.jar; env='-e INBOUND_CREDITS=8 -e EXECUTOR=virtual '; jvm='-Xmn256m -Dclj-grpc.marshaller=direct'; mcs=512; workers=1; VT, 4 cores)
| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB | conns |
|---|---|---|---|---|---|---|---|---|---|---|
| 2000 (warmup) | 1999.8 | 0.11 | 0.56 | 18.69 | 0.0 | 0.148 | 0.0 | 40 | 376 | 1 |
| 160000 | 116235.9 | 0.18 | 106.42 | 170.09 | 2889.6 | 0.021 | 0.0 | 107 | 411 | 1 |
| 240000 | 157588.1 | 0.30 | 45.30 | 67.35 | 614.5 | 0.016 | 0.0 | 203 | 422 | 1 |
| 320000 | 234061.5 | 0.18 | 11.98 | 44.29 | 531.6 | 0.013 | 0.0 | 203 | 429 | 1 |
| 400000 | 254114.6 | 0.26 | 21.02 | 95.22 | 5843.8 | 0.012 | 0.0 | 80 | 443 | 1 |

