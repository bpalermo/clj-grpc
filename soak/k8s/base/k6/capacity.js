// Capacity search for ONE arm: ramping arrival rate 200 -> 2400 req/s in
// 2-minute steps. The knee shows up in Prometheus as dropped_iterations and
// the p99-vs-offered-rate curve; no thresholds — exceeding capacity is the
// point. TARGET_KIND=grpc|rest selects the client.
import grpc from 'k6/net/grpc';
import http from 'k6/http';
import { check } from 'k6';

const KIND = __ENV.TARGET_KIND || 'grpc';
const ADDR = __ENV.TARGET_ADDR; // host:port for grpc, full URL for rest
const PROTO_DIR = __ENV.PROTO_DIR || '/test';

const client = new grpc.Client();
if (KIND === 'grpc') {
  client.load([PROTO_DIR], 'greeter.proto');
}

const stages = [];
for (let rate = 200; rate <= 2400; rate += 200) {
  stages.push({ target: rate, duration: '10s' }); // quick climb to the step
  stages.push({ target: rate, duration: '110s' }); // hold
}

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 200,
      timeUnit: '1s',
      stages,
      preAllocatedVUs: 100,
      maxVUs: 1500,
      exec: KIND === 'grpc' ? 'grpcCall' : 'restCall',
    },
  },
};

export function grpcCall() {
  if (__ITER === 0) {
    client.connect(ADDR, { plaintext: true });
  }
  const resp = client.invoke('acme.greeter.Greeter/SayHello', { name: 'world' });
  check(resp, { ok: (r) => r && r.status === grpc.StatusOK });
}

const params = { headers: { 'Content-Type': 'application/json' } };
const body = JSON.stringify({ name: 'world' });

export function restCall() {
  const resp = http.post(ADDR, body, params);
  check(resp, { ok: (r) => r.status === 200 });
}
