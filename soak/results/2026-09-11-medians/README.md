# Replicates — 2026-09-11

Three runs per ladder rung on a shared ramp that runs past the knee (REST reuses
the 2026-09-10 recheck run as replicate 1), interleaved unary/stream so drift
lands on every replicate alike, plus a second 2-core interop-vs-compiled
streaming pair. Node CPU sampled on all three hosts, driver sampled throughout.
Conclusions in `../../soak-results.md`.

| rung | peaks | median | spread |
|---|---|---|---|
| rest-h1 | 795 / 781 / 806 | 795 | 3.1% |
| rest-h2c | 800 / 800 / 800 | 800 | 0.1% |
| unary | 6,541 / 6,618 / 6,358 | 6,541 | 4.0% |
| stream | 14,009 / 13,966 / 14,266 | 14,009 | 2.1% |

2-core pair 2: interop −12.1% CPU/msg, +8.7% peak, every step negative; pair 1
(2026-09-09) was −8.6% / +7.5%. Both host-limited (node > 4.0 of 4).
