#!/usr/bin/env bash
# Failover demonstration on the RUNNING high-availability stack (docker compose up -d).
#   ./scripts/failover-demo.sh [base-url]        (default http://localhost:8080)
#
# One at a time, it KILLS (SIGKILL, no graceful shutdown) one API gateway, one Eureka server and one
# booking-service replica, and while each is down it:
#   * sends a burst of public API requests through the load balancer and counts the answers, and
#   * runs a complete booking (search → create → simulated payment → CONFIRMED via RabbitMQ).
# Every killed container is started again before the next step. Client requests are NOT retried by this
# script: every request is counted exactly once, so the numbers show what a user would have seen.
# Requires: docker, curl, jq. Exit code is non-zero if any request or booking failed.
set -uo pipefail
cd "$(dirname "$0")/.."

BASE="${1:-http://localhost:8080}"
API="$BASE/api/v1"
BURST="${BURST:-30}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
TOTAL_FAILED=0
SUMMARY=()

say() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*" >&2; }  # progress goes to stderr; functions return results on stdout
section() { printf '\n== %s ==\n' "$*" >&2; }

wait_healthy() { # wait_healthy <container-id> [seconds]
  local id="$1" deadline=$((SECONDS + ${2:-180})) state=""
  while (( SECONDS < deadline )); do
    state=$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null)
    [[ "$state" == "healthy" ]] && return 0
    sleep 2
  done
  say "container $id not healthy after ${2:-180}s (state: $state)"
  return 1
}

container() { docker compose ps -q "$1" | head -1; }

eureka_status() { # eureka_status <host-port> <APP> -> instance statuses, comma separated
  bash -c 'set -a; . ./.env; set +a; curl -s -m 3 -u "$EUREKA_USERNAME:$EUREKA_PASSWORD" -H "Accept: application/json" '"http://localhost:$1/eureka/apps/$2"' 2>/dev/null' \
    | jq -r '[.application.instance[]?.status] | join(",")' 2>/dev/null || echo "unreachable"
}

burst() { # burst <label>: BURST public requests through the load balancer; prints ok/failed and gateway split
  local ok=0 failed=0 code instance
  : > "$WORK/instances"
  for _ in $(seq 1 "$BURST"); do
    code=$(curl -s -m 20 -o /dev/null -D "$WORK/headers" -w '%{http_code}' "$API/rooms?size=5")
    instance=$(grep -i '^x-gateway-instance:' "$WORK/headers" | tr -d '\r' | awk '{print $2}')
    [[ -n "$instance" ]] && echo "$instance" >> "$WORK/instances"
    if [[ "$code" == "200" ]]; then ok=$((ok + 1)); else failed=$((failed + 1)); fi
  done
  local split
  split=$(sort "$WORK/instances" | uniq -c | awk '{printf "%s×%s ", $2, $1}')
  say "$1: $ok/$BURST requests succeeded, $failed failed; answered by: ${split:-none}"
  echo "$ok/$BURST"
}

register() {
  local email="failover-$(date +%s)-$RANDOM@example.test"
  curl -s -m 20 -X POST "$API/auth/register" -H 'Content-Type: application/json' -H 'X-Requested-With: XMLHttpRequest' \
    -d "{\"email\":\"$email\",\"password\":\"correct-horse-42\",\"fullName\":\"Failover Demo\"}" | jq -r '.accessToken // empty'
}

date_plus() { TZ=Asia/Kolkata date -v+"$1"d +%F 2>/dev/null || TZ=Asia/Kolkata date -d "+$1 days" +%F; }

booking_flow() { # booking_flow <label>: one complete booking; prints OK or FAILED(reason)
  local token="$1" label="$2" offset in out room body code bid pid status=""
  offset=$((60 + RANDOM % 250)); in=$(date_plus "$offset"); out=$(date_plus $((offset + 1)))
  room=$(curl -s -m 20 "$API/rooms?checkIn=$in&checkOut=$out&guests=1&size=20" | jq -r '.content[0].id // empty')
  [[ -z "$room" ]] && { say "$label booking: FAILED (no room found)"; echo FAILED; return; }
  body="{\"roomId\":\"$room\",\"checkIn\":\"$in\",\"checkOut\":\"$out\",\"guests\":1,\"primaryGuest\":{\"fullName\":\"Failover Demo\",\"email\":\"guest@example.test\"}}"
  code=$(curl -s -m 30 -o "$WORK/booking" -w '%{http_code}' -X POST "$API/bookings" -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: failover-$RANDOM$RANDOM$RANDOM" -d "$body")
  [[ "$code" != "201" ]] && { say "$label booking: FAILED (create → HTTP $code $(jq -r '.code // empty' "$WORK/booking"))"; echo FAILED; return; }
  bid=$(jq -r .id "$WORK/booking"); pid=$(jq -r .paymentId "$WORK/booking")
  code=$(curl -s -m 30 -o "$WORK/pay" -w '%{http_code}' -X POST "$API/payments/$pid/confirm" -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: failover-pay-$RANDOM$RANDOM" -d '{"paymentMethodToken":"sim_card_success"}')
  [[ "$code" != "200" ]] && { say "$label booking: FAILED (pay → HTTP $code)"; echo FAILED; return; }
  for _ in $(seq 1 45); do
    status=$(curl -s -m 10 "$API/bookings/$bid/status" -H "Authorization: Bearer $token" | jq -r '.status // empty')
    [[ "$status" == "CONFIRMED" ]] && break
    sleep 1
  done
  if [[ "$status" == "CONFIRMED" ]]; then
    say "$label booking $(jq -r .reference "$WORK/booking"): created, paid, CONFIRMED"
    echo OK
  else
    say "$label booking: FAILED (status $status after 45 s)"
    echo FAILED
  fi
}

record() { # record <step> <ok/total> <booking-result>
  SUMMARY+=("$(printf '%-38s %-10s %s' "$1" "$2" "$3")")
  local ok="${2%/*}" total="${2#*/}"
  TOTAL_FAILED=$((TOTAL_FAILED + total - ok))
  [[ "$3" == "OK" ]] || TOTAL_FAILED=$((TOTAL_FAILED + 1))
}

section "0. Starting point"
docker compose ps --format '{{.Service}}\t{{.Status}}' | grep -E 'api-gateway|discovery|booking-service|gateway-lb' | sort >&2
say "Eureka-1 BOOKING-SERVICE: $(eureka_status 8761 BOOKING-SERVICE) · Eureka-2: $(eureka_status 8762 BOOKING-SERVICE)"
TOKEN=$(register)
[[ -z "$TOKEN" ]] && { say "could not register a demo customer"; exit 1; }
B=$(burst "baseline" | tail -1)
F=$(booking_flow "$TOKEN" "baseline" | tail -1)
record "baseline (everything up)" "$B" "$F"

section "1. Kill API gateway 1 (load balancer must route around it)"
GW1=$(container api-gateway-1)
docker kill "$GW1" >/dev/null && say "killed api-gateway-1 ($GW1)"
B=$(burst "gateway-1 down" | tail -1)
F=$(booking_flow "$TOKEN" "gateway-1 down" | tail -1)
record "api-gateway-1 killed" "$B" "$F"
docker start "$GW1" >/dev/null && wait_healthy "$GW1" && say "api-gateway-1 restarted and healthy"

section "2. Kill Eureka server 1 (clients and gateways use the peer)"
DS1=$(container discovery-server-1)
docker kill "$DS1" >/dev/null && say "killed discovery-server-1 ($DS1)"
B=$(burst "eureka-1 down" | tail -1)
F=$(booking_flow "$TOKEN" "eureka-1 down" | tail -1)
record "discovery-server-1 killed" "$B" "$F"
say "restarting notification-service while eureka-1 is down: it must register with eureka-2"
NS=$(container notification-service)
docker restart "$NS" >/dev/null && wait_healthy "$NS"
for _ in $(seq 1 30); do [[ "$(eureka_status 8762 NOTIFICATION-SERVICE)" == *UP* ]] && break; sleep 2; done
say "Eureka-2 NOTIFICATION-SERVICE: $(eureka_status 8762 NOTIFICATION-SERVICE) (Eureka-1: $(eureka_status 8761 NOTIFICATION-SERVICE))"
docker start "$DS1" >/dev/null && wait_healthy "$DS1" && say "discovery-server-1 restarted and healthy"
for _ in $(seq 1 30); do [[ "$(eureka_status 8761 NOTIFICATION-SERVICE)" == *UP* ]] && break; sleep 2; done
say "after restart, Eureka-1 NOTIFICATION-SERVICE: $(eureka_status 8761 NOTIFICATION-SERVICE) (replicated from its peer)"

section "3. Kill one booking-service replica (gateway retries the other; RabbitMQ re-delivers its messages)"
BS1=$(docker compose ps -q booking-service | head -1)
docker kill "$BS1" >/dev/null && say "killed booking-service replica ($BS1)"
OK=0; FAILED=0
for _ in $(seq 1 10); do
  code=$(curl -s -m 30 -o /dev/null -w '%{http_code}' "$API/bookings/my" -H "Authorization: Bearer $TOKEN")
  if [[ "$code" == "200" ]]; then OK=$((OK + 1)); else FAILED=$((FAILED + 1)); fi
done
say "booking replica down: $OK/10 'my bookings' requests succeeded, $FAILED failed"
F=$(booking_flow "$TOKEN" "booking replica down" | tail -1)
record "one booking-service replica killed" "$OK/10" "$F"
docker start "$BS1" >/dev/null && wait_healthy "$BS1" && say "booking-service replica restarted and healthy"
for _ in $(seq 1 30); do [[ "$(eureka_status 8761 BOOKING-SERVICE)" == "UP,UP" ]] && break; sleep 2; done
say "Eureka BOOKING-SERVICE instances: $(eureka_status 8761 BOOKING-SERVICE)"

section "Summary (requests counted once each; no client-side retries)"
printf '%-38s %-10s %s\n' "step" "requests" "full booking"
for line in "${SUMMARY[@]}"; do echo "$line"; done
if (( TOTAL_FAILED == 0 )); then
  echo "FAILOVER DEMO PASSED: no failed request or booking"
else
  echo "FAILOVER DEMO: $TOTAL_FAILED failure(s) — see the lines above"
  exit 1
fi
