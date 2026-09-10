# Prediction — recorded before the runs

## The hypothesis the hardware suggested

talos-main is Raspberry Pi CM5 on a DeskPi Super6C: 4 cores and 8 GB per node,
flannel VXLAN between them. Kernel network work — softirq receive, VXLAN
encap/decap, the CNI path — is charged to the NODE, not to the pod's cgroup. So
an arm holding 1.45 of its 2-core quota is only idle *inside* the cgroup, and
every CPU number this campaign has published measures one side of that line.

Talos publishes per-CPU counters natively (`talosctl get cpustat`), and at idle
they already show the asymmetry: **65% of this node's lifetime softirq sits on
cpu0**, and a fresh 31-second sample reads cpu0 at 0.091 softirq cores against
0.020 on each of the other three. That is the single-NIC-receive-queue pattern.
If cpu0 saturates under load, the node has a roughly one-core network ceiling
regardless of the pod's quota — which would explain a ceiling that is invariant
to connections, to codec, and to clj-protobuf version while leaving pod CPU
visibly idle.

## Run A — realistic tier, the reference, with node sampling

Same arm and settings that produced 23,316 msg/s at 1.49 cores on chart 0.2.21.
The only new thing is the instrument.

- **cpu0 softirq at or above ~0.85 cores at the top steps** → the node's network
  path is the ceiling. This is the clean confirmation and it ends the search.
- **cpu0 softirq well below saturation (say under 0.6)** → the network path is
  not the binding constraint, and the ceiling is back inside the JVM or the
  transport. Item 2's wall profile becomes the next instrument.
- **Node total near 4.0 cores with softirq low** → something else on the node is
  competing; check what, since worker-02 also carries aether workloads.

## Run B — tiny tier, 7 bytes against realistic's 1,025

Ramp raised to 24,000-56,000 because a size-dependent ceiling should clear the
realistic one by a wide margin.

What tiny actually changes: bytes on the wire, protobuf work, Clojure value
size, allocation. What it does NOT change: message count, HTTP/2 framing count,
wakeups, or softirq per packet.

- **Stays near 25,000** → the ceiling is per-message overhead independent of
  payload size. Points at wakeups, framing, or per-packet kernel work.
- **Rises well above** → the ceiling is size-dependent. That includes both bytes
  on the wire AND per-message JVM work, so it does not by itself name the
  network; Run A's softirq column is what separates those.

Threshold: under 15% reads as flat, over 50% as size-dependent.

## The confound to state in advance

**The driver is single-worker and will become the limit somewhere above
~40,000 msg/s.** It used 0.60 cores at 25,054 msg/s with a 1-core Guaranteed
budget, so linear extrapolation puts its own ceiling near 42,000. More CPU would
not help: `--concurrency 1` is one event loop and one thread.

So Run B can distinguish "about 25,000" from "well above 25,000" and cannot
measure how far above. If tiny lands near 40,000 with the driver throttling,
the honest reading is "at least 40,000, driver-limited" — which still answers
the question asked, and must not be written as a measured ceiling.
