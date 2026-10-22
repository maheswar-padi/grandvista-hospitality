#!/usr/bin/env bash
# Deploys (or updates) GrandVista on a single Linux VM with Docker, behind Caddy with automatic HTTPS.
# Run ON THE VM from the repository directory:
#   ./scripts/deploy.sh            # first time: creates .env, then asks you to set GV_DOMAIN and ACME_EMAIL
#   ./scripts/deploy.sh --pull     # later: git pull, rebuild, restart changed services, smoke-test
# Secrets stay in .env on the VM (mode 600); nothing is printed.
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.prod.yml)

if [[ "${1:-}" == "--pull" ]]; then
  git pull --ff-only
fi

./scripts/init-env.sh
for key in GV_DOMAIN ACME_EMAIL; do
  if ! grep -qE "^${key}=.+" .env; then
    echo "Set ${key} in .env first (see docs/deployment/DEPLOYMENT.md#cloud-deployment), then run this script again." >&2
    exit 1
  fi
done
DOMAIN=$(grep -E '^GV_DOMAIN=' .env | cut -d= -f2-)

echo "Building and starting the stack for https://${DOMAIN} …"
"${COMPOSE[@]}" up -d --build --remove-orphans --wait --wait-timeout 900
"${COMPOSE[@]}" ps --format '{{.Service}}\t{{.Status}}' | sort

echo "Smoke test against https://${DOMAIN} (e-mail checks skipped: Mailpit is not published in production) …"
MAILPIT_URL= ./scripts/e2e-smoke.sh "https://${DOMAIN}"
