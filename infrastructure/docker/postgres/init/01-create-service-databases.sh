#!/bin/bash
# Runs once, when the PostgreSQL data volume is first initialised.
# Creates one database and one owning login role per service (database-per-service ownership):
# a service's credentials cannot read or write another service's tables.
set -euo pipefail

create_service_db() {
  local db="$1" user="$2" password="$3"
  if [[ -z "$password" ]]; then
    echo "Missing password for $user" >&2
    exit 1
  fi
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
       -v db="$db" -v usr="$user" -v pwd="$password" <<'SQL'
CREATE ROLE :"usr" LOGIN PASSWORD :'pwd';
CREATE DATABASE :"db" OWNER :"usr";
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
SQL
  echo "Created database $db owned by $user"
}

create_service_db grandvista_auth    grandvista_auth    "${AUTH_DB_PASSWORD:-}"
create_service_db grandvista_room    grandvista_room    "${ROOM_DB_PASSWORD:-}"
create_service_db grandvista_booking grandvista_booking "${BOOKING_DB_PASSWORD:-}"
create_service_db grandvista_payment grandvista_payment "${PAYMENT_DB_PASSWORD:-}"
