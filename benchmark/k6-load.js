import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    readHeavy: {
      executor: 'constant-vus',
      vus: __ENV.K6_VUS || 500,
      duration: __ENV.K6_DURATION || '60s',
    }
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    http_req_duration: ['p(99)<10000'],
  }
};

const BASE = __ENV.K6_BASE_URL || 'http://localhost:9090';

export default function () {
  const roll = Math.random();

  if (roll < 0.6) {
    http.get(`${BASE}/api/webhooks`);
  } else if (roll < 0.85) {
    const body = JSON.stringify({
      source: `k6-vu${__VU}`,
      eventType: 'load-test',
      payload: '{"i":' + __ITER + '}'
    });
    const params = { headers: { 'Content-Type': 'application/json' } };
    http.post(`${BASE}/api/webhooks`, body, params);
  } else {
    http.get(`${BASE}/api/system/info`);
  }
}
