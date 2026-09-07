### rest-h1:http1:realistic:600 (nh-rest-h1-http1-realistic-09071559; restarts=0)
| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 178.1 | 30.53 | 1695.55 | 2854.22 | 21.9 | 4.689 | 77.4 | 77 | 417 |
| 600 | 593.5 | 7.28 | 275.48 | 503.71 | 6.4 | 1.637 | 16.0 | 95 | 417 |

### grpc-jvm:grpc-unary:realistic:4000 (nh-grpc-jvm-grpc-unary-realistic-09071610; restarts=0)
| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.85 | 5186.78 | 8197.51 | 0.0 | 1.345 | 9.6 | 26 | 167 |
| 4000 | 3409.2 | 108.82 | 8511.82 | 11377.05 | 590.1 | 0.274 | 32.9 | 35 | 201 |

### rest-h1:http1:realistic:200 400 600 (nh-rest-h1-http1-realistic-09071619; restarts=0)
| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 179.6 | 90.30 | 1628.57 | 2756.84 | 20.4 | 5.128 | 352.5 | 130 | 350 |
| 200 | 199.7 | 2.98 | 33.46 | 285.70 | 0.3 | 2.178 | 1.8 | 130 | 351 |
| 400 | 399.7 | 2.86 | 51.27 | 110.39 | 0.3 | 1.769 | 4.1 | 80 | 351 |
| 600 | 595.9 | 4.04 | 177.12 | 470.02 | 4.0 | 1.614 | 19.9 | 91 | 351 |

### grpc-jvm:grpc-unary:realistic:1000 2000 3000 (nh-grpc-jvm-grpc-unary-realistic-09071632; restarts=0)
| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.85 | 4755.55 | 8159.23 | 0.0 | 1.329 | 29.1 | 25 | 159 |
| 1000 | 1000.0 | 1.55 | 33.74 | 72.99 | 0.0 | 0.524 | 2.4 | 26 | 163 |
| 2000 | 1996.5 | 2.50 | 2472.15 | 5526.78 | 3.5 | 0.390 | 16.8 | 31 | 182 |
| 3000 | 2999.8 | 3.44 | 238.15 | 550.27 | 0.1 | 0.271 | 1.0 | 31 | 183 |

### grpc-jvm:grpc-stream:realistic:2000 3500 5000:40 (nh-grpc-jvm-grpc-stream-realistic-09071644; restarts=0)
| offered | delivered/s | p50 ms | p99 ms | p999 ms | knee/s | cpu ms/req | throttled s | heap MB | rss MB |
|---|---|---|---|---|---|---|---|---|---|
| 200 (warmup) | 200.0 | 1.59 | 1886.72 | 2680.82 | 0.0 | 0.984 | 8.5 | 22 | 172 |
| 2000 | 1999.8 | 1.36 | 107.36 | 240.12 | 0.0 | 0.308 | 3.6 | 19 | 178 |
| 3500 | 3499.8 | 2.00 | 216.77 | 290.18 | 0.0 | 0.214 | 2.7 | 26 | 182 |
| 5000 | 4999.4 | 3.11 | 188.84 | 269.17 | 0.0 | 0.161 | 0.8 | 26 | 186 |

