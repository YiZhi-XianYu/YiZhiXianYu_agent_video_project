import http from 'k6/http';
import { check, sleep } from 'k6';

// Baseline smoke profile. Set BASE_URL and AUTH_COOKIE for an authenticated environment.
export const options = {
  scenarios: {
    api_baseline: { executor: 'constant-vus', vus: Number(__ENV.VUS || 1), duration: __ENV.DURATION || '30s' },
  },
  thresholds: { http_req_failed: ['rate<0.05'], http_req_duration: ['p(95)<2000'] },
};

export default function () {
  const base = __ENV.BASE_URL || 'http://127.0.0.1:8080';
  const headers = __ENV.AUTH_COOKIE ? { Cookie: __ENV.AUTH_COOKIE } : {};
  const response = http.get(`${base}/actuator/health`, { headers });
  check(response, { 'health is 200': (r) => r.status === 200 });
  sleep(1);
}
