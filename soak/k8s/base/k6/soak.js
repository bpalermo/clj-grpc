// Three-arm soak: gRPC native vs gRPC JVM vs REST (Pedestal), identical
// payload and arrival rate, run concurrently — each server on its own worker,
// this runner on a fourth. RATE (per arm, req/s) and DURATION come from the
// TestRun env; thresholds fail the run loudly rather than letting a sick arm
// soak quietly.
import grpc from 'k6/net/grpc';
import http from 'k6/http';
import { check } from 'k6';

const RATE = Number(__ENV.RATE || 200);
const DURATION = __ENV.DURATION || '2h';
// The k6-operator mounts the script ConfigMap at /test.
const PROTO_DIR = __ENV.PROTO_DIR || '/test';

const GRPC_NATIVE = __ENV.GRPC_NATIVE_ADDR || 'grpc-native.clj-grpc-soak.svc.cluster.local:8080';
const GRPC_JVM = __ENV.GRPC_JVM_ADDR || 'grpc-jvm.clj-grpc-soak.svc.cluster.local:8080';
const REST_URL = __ENV.REST_URL || 'http://rest.clj-grpc-soak.svc.cluster.local:8080/hello';

const nativeClient = new grpc.Client();
nativeClient.load([PROTO_DIR], 'greeter.proto');
const jvmClient = new grpc.Client();
jvmClient.load([PROTO_DIR], 'greeter.proto');

function arrival(exec) {
  return {
    executor: 'constant-arrival-rate',
    rate: RATE,
    timeUnit: '1s',
    duration: DURATION,
    preAllocatedVUs: 20,
    maxVUs: 200,
    exec,
  };
}

export const options = {
  scenarios: {
    grpc_native: arrival('grpcNative'),
    grpc_jvm: arrival('grpcJvm'),
    rest: arrival('rest'),
  },
  thresholds: {
    'grpc_req_duration{scenario:grpc_native}': ['p(99)<500'],
    'grpc_req_duration{scenario:grpc_jvm}': ['p(99)<500'],
    'http_req_duration{scenario:rest}': ['p(99)<500'],
    checks: ['rate>0.999'],
  },
};

function sayHello(client, addr) {
  if (__ITER === 0) {
    client.connect(addr, { plaintext: true });
  }
  const resp = client.invoke('acme.greeter.Greeter/SayHello', { name: 'world' });
  check(resp, {
    'grpc status OK': (r) => r && r.status === grpc.StatusOK,
    'grpc echo': (r) => r && r.message && r.message.message === 'Hello world',
  });
}

export function grpcNative() {
  sayHello(nativeClient, GRPC_NATIVE);
}

export function grpcJvm() {
  sayHello(jvmClient, GRPC_JVM);
}

const restParams = { headers: { 'Content-Type': 'application/json' } };

export function rest() {
  const resp = http.post(REST_URL, JSON.stringify({ name: 'world' }), restParams);
  check(resp, {
    'http 200': (r) => r.status === 200,
    'rest echo': (r) => {
      try {
        return r.json('message') === 'Hello world';
      } catch (_) {
        return false;
      }
    },
  });
}
