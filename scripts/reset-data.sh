#!/usr/bin/env bash
# Stops the stack and PERMANENTLY deletes its data volumes (PostgreSQL databases and uploaded room photos).
# Asks for confirmation; pass --yes to skip the prompt (e.g. in CI).
set -euo pipefail
cd "$(dirname "$0")/.."

echo "This will stop GrandVista and permanently delete:"
echo "  - volumes grandvista-{auth,room,booking,payment,notification}-db-data and grandvista-rabbitmq-data"
echo "  - volume grandvista-room-media     (uploaded room photographs)"
if [[ "${1:-}" != "--yes" ]]; then
  read -r -p "Type DELETE to continue: " answer
  [[ "$answer" == "DELETE" ]] || { echo "Aborted — nothing was deleted."; exit 1; }
fi
docker compose down --volumes --remove-orphans
echo "Data removed. Start fresh with: docker compose up -d"
