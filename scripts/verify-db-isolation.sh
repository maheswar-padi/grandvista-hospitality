#!/usr/bin/env bash
# Verifies database-per-service isolation on the RUNNING stack:
#   1. each service has its own PostgreSQL container holding only that service's tables;
#   2. a service's credentials do not exist on any other service's database server;
#   3. each service's JDBC URL points only at its own database container.
# Prints table names and pass/fail lines only — never passwords.
set -uo pipefail
cd "$(dirname "$0")/.."
PASS=0; FAIL=0
ok() { PASS=$((PASS + 1)); printf '  \033[32m✔\033[0m %s\n' "$1"; }
ko() { FAIL=$((FAIL + 1)); printf '  \033[31m✘\033[0m %s\n' "$1"; }
SERVICES=(auth room booking payment notification)

for svc in "${SERVICES[@]}"; do
  echo "${svc}-db:"
  tables=$(docker compose exec -T "${svc}-db" sh -c \
    "psql -U grandvista_${svc} -d grandvista_${svc} -Atc \"select string_agg(tablename, ', ' order by tablename) from pg_tables where schemaname='public'\"" 2>&1)
  [[ -n "$tables" && "$tables" != *error* ]] && ok "grandvista_${svc} tables: $tables" || ko "cannot list tables: $tables"
  dbs=$(docker compose exec -T "${svc}-db" sh -c "psql -U grandvista_${svc} -d grandvista_${svc} -Atc \"select string_agg(datname, ',') from pg_database where not datistemplate and datname <> 'postgres'\"")
  [[ "$dbs" == "grandvista_${svc}" ]] && ok "server holds only database grandvista_${svc}" || ko "unexpected databases on ${svc}-db: $dbs"
  for other in "${SERVICES[@]}"; do
    [[ "$other" == "$svc" ]] && continue
    exists=$(docker compose exec -T "${svc}-db" sh -c "psql -U grandvista_${svc} -d grandvista_${svc} -Atc \"select count(*) from pg_roles where rolname='grandvista_${other}'\"")
    [[ "$exists" == "0" ]] && ok "role grandvista_${other} does not exist on ${svc}-db" || ko "role grandvista_${other} exists on ${svc}-db"
  done
  super=$(docker compose exec -T "${svc}-db" sh -c "psql -U grandvista_${svc} -d grandvista_${svc} -Atc \"select rolsuper from pg_roles where rolname='grandvista_${svc}'\"")
  [[ "$super" == "f" ]] && ok "grandvista_${svc} is not a superuser" || ko "grandvista_${svc} is a superuser"
  url=$(docker compose exec -T "${svc}-service" printenv DB_URL 2>/dev/null)
  [[ "$url" == "jdbc:postgresql://${svc}-db:5432/grandvista_${svc}" ]] && ok "${svc}-service connects to $url" || ko "${svc}-service DB_URL is '$url'"
done

printf '\npassed: %d   failed: %d\n' "$PASS" "$FAIL"
[[ $FAIL -eq 0 ]]
