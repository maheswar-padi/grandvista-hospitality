#!/usr/bin/env bash
# One command to run GrandVista locally: creates .env and the JWT keys if missing, builds and starts all
# containers, waits until every one is healthy, then prints the URLs. Re-running it is safe.
#   ./scripts/start.sh            # standard stack
#   ./scripts/start.sh --observability   # also Prometheus + Grafana
set -euo pipefail
cd "$(dirname "$0")/.."
PROFILE=()
[[ "${1:-}" == "--observability" ]] && PROFILE=(--profile observability)
./scripts/init-env.sh
echo "Building and starting (first run: several minutes) …"
docker compose ${PROFILE[@]+"${PROFILE[@]}"} up -d --build --wait --wait-timeout 900
cat <<'URLS'

GrandVista is running:
  Web app            http://localhost:3000
  API (2 gateways)   http://localhost:8080/api/v1     Swagger: http://localhost:8080/swagger-ui.html
  Eureka 1 / 2       http://localhost:8761  /  http://localhost:8762
  RabbitMQ           http://localhost:15672
  Mailpit (e-mails)  http://localhost:8025
URLS
[[ -n "${PROFILE[*]+x}" ]] && echo "  Prometheus         http://localhost:9090      Grafana: http://localhost:3001"
echo
echo "Sign-in details are in .env (GV_BOOTSTRAP_ADMIN_*, GV_DEMO_CUSTOMER_*, GV_DEMO_STAFF_*, EUREKA_*, RABBITMQ_*)."
echo "Check everything end to end:  ./scripts/e2e-smoke.sh"
