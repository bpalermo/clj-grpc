### local:off-vt-1conn (image=java21-debian12@sha256:f34fd3e4e2d7a246d764d0614f5e6ffb3a735930723fac4cfc25a72798950262; jar=marsh.jar; env='-e INBOUND_CREDITS=8 -e EXECUTOR=virtual '; jvm='-Xmn256m'; mcs=512; workers=1; VT, 4 cores)
| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB | conns |
|---|---|---|---|---|---|---|---|---|---|---|
| 2000 (warmup) | 1999.8 | 0.11 | 0.63 | 20.30 | 0.0 | 0.142 | 0.0 | 40 | 384 | 1 |
| 160000 | 159561.0 | 0.11 | 37.04 | 95.82 | 436.0 | 0.016 | 0.0 | 169 | 414 | 1 |
| 240000 | 239879.5 | 0.16 | 11.54 | 31.00 | 114.8 | 0.013 | 0.0 | 171 | 419 | 1 |
| 320000 | 299849.8 | 0.43 | 46.46 | 81.12 | 20134.7 | 0.011 | 0.0 | 171 | 451 | 1 |
| 400000 | 384905.0 | 1.62 | 52.06 | 134.01 | 15081.9 | 0.009 | 0.0 | 94 | 457 | 1 |

### local:on-vt-1conn (image=java21-debian12@sha256:f34fd3e4e2d7a246d764d0614f5e6ffb3a735930723fac4cfc25a72798950262; jar=marsh.jar; env='-e INBOUND_CREDITS=8 -e EXECUTOR=virtual '; jvm='-Xmn256m -Dclj-grpc.marshaller=direct'; mcs=512; workers=1; VT, 4 cores)
| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB | conns |
|---|---|---|---|---|---|---|---|---|---|---|
| 2000 (warmup) | 1999.9 | 0.12 | 3.47 | 19.15 | 0.0 | 0.148 | 0.0 | 105 | 394 | 1 |
| 160000 | 159212.9 | 0.13 | 48.45 | 117.11 | 784.4 | 0.016 | 0.0 | 131 | 418 | 1 |
| 240000 | 233717.0 | 0.18 | 70.10 | 124.26 | 6278.9 | 0.012 | 0.0 | 131 | 428 | 1 |
| 320000 | 298184.7 | 0.43 | 67.27 | 226.32 | 21791.8 | 0.011 | 0.0 | 101 | 433 | 1 |
| 400000 | 343744.3 | 3.48 | 41.40 | 112.44 | 56207.6 | 0.009 | 0.0 | 212 | 453 | 1 |

### local:off-vt-8conn (image=java21-debian12@sha256:f34fd3e4e2d7a246d764d0614f5e6ffb3a735930723fac4cfc25a72798950262; jar=marsh.jar; env='-e INBOUND_CREDITS=8 -e EXECUTOR=virtual -e WORKER_THREADS=2'; jvm='-Xmn256m'; mcs=5; workers=4; VT, 4 cores)
| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB | conns |
|---|---|---|---|---|---|---|---|---|---|---|
| 2000 (warmup) | 1999.8 | 0.13 | 3.36 | 16.10 | 0.0 | 0.146 | 0.0 | 37 | 391 | 8 |
| 160000 | 156668.9 | 0.13 | 114.33 | 189.92 | 3328.3 | 0.021 | 0.0 | 119 | 444 | 8 |
| 240000 | 239989.2 | 0.13 | 3.85 | 20.12 | 1.1 | 0.016 | 0.0 | 162 | 445 | 8 |
| 320000 | 307791.1 | 0.98 | 52.76 | 69.76 | 12201.7 | 0.012 | 0.0 | 162 | 461 | 8 |
| 400000 | 373587.2 | 13.77 | 49.85 | 79.06 | 26328.8 | 0.010 | 0.0 | 201 | 471 | 8 |

### local:on-vt-8conn (image=java21-debian12@sha256:f34fd3e4e2d7a246d764d0614f5e6ffb3a735930723fac4cfc25a72798950262; jar=marsh.jar; env='-e INBOUND_CREDITS=8 -e EXECUTOR=virtual -e WORKER_THREADS=2'; jvm='-Xmn256m -Dclj-grpc.marshaller=direct'; mcs=5; workers=4; VT, 4 cores)
| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB | conns |
|---|---|---|---|---|---|---|---|---|---|---|
| 2000 (warmup) | 1999.8 | 0.14 | 0.52 | 16.36 | 0.0 | 0.167 | 0.0 | 114 | 355 | 8 |
| 160000 | 159605.9 | 0.10 | 61.37 | 89.43 | 390.9 | 0.021 | 0.0 | 126 | 421 | 8 |
| 240000 | 239997.2 | 0.11 | 3.40 | 9.94 | 0.0 | 0.016 | 0.0 | 192 | 422 | 8 |
| 320000 | 319906.0 | 0.31 | 6.56 | 27.85 | 77.1 | 0.012 | 0.0 | 192 | 424 | 8 |
| 400000 | 398988.0 | 4.02 | 25.05 | 34.30 | 992.5 | 0.009 | 0.0 | 181 | 431 | 8 |

### local:off-direct-1conn (image=java21-debian12@sha256:f34fd3e4e2d7a246d764d0614f5e6ffb3a735930723fac4cfc25a72798950262; jar=marsh.jar; env='-e INBOUND_CREDITS=8 -e EXECUTOR=direct '; jvm='-Xmn256m'; mcs=512; workers=1; VT, 4 cores)
| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB | conns |
|---|---|---|---|---|---|---|---|---|---|---|
| 2000 (warmup) | 1999.8 | 0.07 | 0.29 | 22.43 | 0.0 | 0.095 | 0.0 | 201 | 388 | 1 |
| 160000 | 141414.2 | 27.57 | 193.18 | 577.60 | 18490.0 | 0.008 | 0.0 | 201 | 419 | 1 |
| 240000 | 144930.7 | 23.02 | 154.26 | 1493.96 | 94790.7 | 0.007 | 0.0 | 107 | 430 | 1 |
| 320000 | 147101.1 | 25.86 | 151.83 | 2567.05 | 172513.6 | 0.007 | 0.0 | 120 | 435 | 1 |
| 400000 | 146795.1 | 10.41 | 132.73 | 2598.11 | 252860.4 | 0.007 | 0.0 | 207 | 444 | 1 |

### local:on-direct-1conn (image=java21-debian12@sha256:f34fd3e4e2d7a246d764d0614f5e6ffb3a735930723fac4cfc25a72798950262; jar=marsh.jar; env='-e INBOUND_CREDITS=8 -e EXECUTOR=direct '; jvm='-Xmn256m -Dclj-grpc.marshaller=direct'; mcs=512; workers=1; VT, 4 cores)
| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB | conns |
|---|---|---|---|---|---|---|---|---|---|---|
| 2000 (warmup) | 1999.7 | 0.08 | 0.34 | 23.35 | 0.0 | 0.097 | 0.0 | 61 | 368 | 1 |
| 160000 | 139156.6 | 47.88 | 105.91 | 145.83 | 20665.6 | 0.008 | 0.0 | 61 | 414 | 1 |
| 240000 | 148975.6 | 25.97 | 269.07 | 1500.84 | 90809.0 | 0.007 | 0.0 | 34 | 424 | 1 |
| 320000 | 143335.7 | 26.14 | 188.90 | 1873.94 | 176342.9 | 0.007 | 0.0 | 96 | 431 | 1 |
| 400000 | 144651.3 | 6.62 | 124.94 | 2197.95 | 255016.4 | 0.007 | 0.0 | 96 | 440 | 1 |

