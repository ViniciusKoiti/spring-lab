import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8070';
const mode = __ENV.MODE || 'sync';
const permits = parseInt(__ENV.PERMITS || '8', 10);
const items = parseInt(__ENV.ITEMS || '500', 10);
const payloadSize = parseInt(__ENV.PAYLOAD_SIZE || '64', 10);
const vus = parseInt(__ENV.VUS || '5', 10);
const ramp = __ENV.RAMP || '10s';
const duration = __ENV.DURATION || '60s';

export const options = {
  scenarios: {
    benchmark: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: ramp, target: vus },
        { duration: duration, target: vus },
      ],
      gracefulRampDown: '0s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<5000'],
  },
};

export default function () {
  const payload = JSON.stringify({
    items: items,
    mode: mode,
    permits: permits,
    payloadSize: payloadSize,
  });

  const params = {
    headers: { 'Content-Type': 'application/json' },
  };

  const res = http.post(`${baseUrl}/enrich/benchmark`, payload, params);

  check(res, {
    'status is 200': (r) => r.status === 200,
  });

  sleep(0.2);
}
