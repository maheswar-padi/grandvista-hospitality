#!/usr/bin/env bash
# Runs load/k6/load-test.js against the running stack through the gateway load balancer.
# Temporarily raises the gateways' per-IP rate limits (all k6 traffic comes from one IP) and restores the
# configured limits afterwards. Writes docs/testing/evidence/k6-summary.json and k6-output.txt.
set -euo pipefail
cd "$(dirname "$0")/.."
restore() {
  echo "Restoring the configured gateway rate limits …"
  docker compose up -d --wait api-gateway-1 api-gateway-2 >/dev/null
}
trap restore EXIT
echo "Raising gateway rate limits for the load test …"
GV_API_RATE_LIMIT=1000000 GV_AUTH_RATE_LIMIT=1000 docker compose up -d --wait api-gateway-1 api-gateway-2 >/dev/null
sleep 15   # let the restarted gateways appear in the load balancer and Eureka caches
mkdir -p docs/testing/evidence
docker run --rm --network grandvista -v "$PWD/load/k6:/scripts:ro" -v "$PWD/docs/testing/evidence:/out" \
  grafana/k6:1.1.0 run --quiet /scripts/load-test.js 2>&1 | tee docs/testing/evidence/k6-output.txt
