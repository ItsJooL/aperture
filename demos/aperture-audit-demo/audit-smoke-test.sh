#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8082}"
WIREMOCK_URL="${WIREMOCK_URL:-http://localhost:8282}"
PASSWORD="${APERTURE_BOOTSTRAP_ADMIN_PASSWORD:-changeme-local-only}"

wait_for() {
  local url="$1"
  local label="$2"
  for _ in $(seq 1 60); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for $label at $url" >&2
  exit 1
}

json_get() {
  python3 -c 'import json,sys; data=json.load(sys.stdin); cur=data
for part in sys.argv[1].split("."):
    cur = cur[int(part)] if part.isdigit() else cur[part]
print(cur)' "$1"
}

has_update_details() {
  python3 - "$1" "$2" <<'PY'
import json
import sys

audit_events = json.loads(sys.argv[1])
wiremock_events = json.loads(sys.argv[2])

def normalize_details(details):
    if isinstance(details, dict) and details.get("type") == "jsonb" and isinstance(details.get("value"), str):
        return json.loads(details["value"])
    return details

def audit_has_update(events):
    for event in events:
        details = normalize_details(event.get("details", {}))
        if (
            event.get("operation") == "UPDATE"
            and details.get("fieldPath") == "name"
            and details.get("before") == "Audit Widget"
            and details.get("after") == "Audit Widget Updated"
        ):
            return True
    return False

def wiremock_has_update(events):
    for request in events.get("requests", []):
        body = json.loads(request["request"]["body"])
        for event in body:
            details = event.get("details", {})
            if (
                event.get("operation") == "UPDATE"
                and details.get("fieldPath") == "name"
                and details.get("before") == "Audit Widget"
                and details.get("after") == "Audit Widget Updated"
                and request.get("wasMatched") is True
            ):
                return True
    return False

sys.exit(0 if audit_has_update(audit_events) and wiremock_has_update(wiremock_events) else 1)
PY
}

wait_for "$BASE_URL/actuator/health" "API"
wait_for "$WIREMOCK_URL/__admin/health" "WireMock"

curl -fsS -X DELETE "$WIREMOCK_URL/__admin/requests" >/dev/null

TOKEN="$(curl -fsS -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"superadmin@framework.local\",\"password\":\"$PASSWORD\"}" | json_get accessToken)"

PRODUCT_ID="$(curl -fsS -X POST "$BASE_URL/api/v1/products" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/vnd.api+json" \
  -H "Accept: application/vnd.api+json" \
  -d '{"data":{"type":"products","attributes":{"name":"Audit Widget","sku":"AUDIT-001","supplier_secret":"contract floor 42","price":19.99}}}' | json_get data.id)"

curl -fsS -X PATCH "$BASE_URL/api/v1/products/$PRODUCT_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/vnd.api+json" \
  -H "Accept: application/vnd.api+json" \
  -d "{\"data\":{\"type\":\"products\",\"id\":\"$PRODUCT_ID\",\"attributes\":{\"name\":\"Audit Widget Updated\"}}}" >/dev/null

for _ in $(seq 1 30); do
  AUDIT_BODY="$(curl -fsS "$BASE_URL/manage/audit?entity=Product&entityId=$PRODUCT_ID" -H "Authorization: Bearer $TOKEN")"
  WIREMOCK_BODY="$(curl -fsS "$WIREMOCK_URL/__admin/requests?url=/siem/audit")"
  if has_update_details "$AUDIT_BODY" "$WIREMOCK_BODY"; then
    echo "Audit smoke test passed: JDBC audit log and WireMock SIEM sink both received before/after details."
    exit 0
  fi
  sleep 1
done

echo "Audit smoke test failed: expected fieldPath before/after details in both JDBC and WireMock." >&2
echo "JDBC response: $AUDIT_BODY" >&2
echo "WireMock response: $WIREMOCK_BODY" >&2
exit 1
