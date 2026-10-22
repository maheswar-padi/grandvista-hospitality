#!/bin/bash
# Runs once, when a service's PostgreSQL data volume is first initialised.
# Each service has its own PostgreSQL container (database-per-service). The container's superuser is used only
# for this initialisation; the service connects with a separate, non-superuser login role that owns its database.
set -euo pipefail

: "${SERVICE_DB:?SERVICE_DB is required}"
: "${SERVICE_USER:?SERVICE_USER is required}"
: "${SERVICE_PASSWORD:?SERVICE_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
     -v db="$SERVICE_DB" -v usr="$SERVICE_USER" -v pwd="$SERVICE_PASSWORD" <<'SQL'
CREATE ROLE :"usr" LOGIN PASSWORD :'pwd' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE :"db" OWNER :"usr";
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
REVOKE ALL ON DATABASE postgres FROM PUBLIC;
SQL
echo "Created database $SERVICE_DB owned by $SERVICE_USER"
