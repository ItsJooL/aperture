#!/bin/bash
set -euo pipefail

# Smoke test for rate-limit demonstration
# Recreates the api-server with tight IP bucket (capacity=2), triggers a 429,
# validates rate-limit headers, then restores the default limit.

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DEMO_DIR"

API_URL="${API_URL:-http://localhost:8080}"
ADMIN_USER="${ADMIN_USER:-admin@acme.com}"
ADMIN_PASS="${ADMIN_PASS:-AcmeAdmin123!}"
TIGHT_CAPACITY=2

echo "=== Rate-Limit Smoke Test ==="
echo "Recreating api-server with APERTURE_RATE_LIMIT_IP_CAPACITY=$TIGHT_CAPACITY..."

# Recreate api-server with tight capacity
APERTURE_RATE_LIMIT_IP_CAPACITY=$TIGHT_CAPACITY \
  docker compose up -d --force-recreate api-server

# Wait for api-server to become healthy
echo "Waiting for api-server to become healthy..."
max_attempts=30
attempt=0
while [ $attempt -lt $max_attempts ]; do
  if docker compose exec api-server wget -q --tries=1 --spider http://localhost:8080/actuator/health 2>/dev/null; then
    echo "API server is healthy"
    break
  fi
  attempt=$((attempt + 1))
  sleep 2
done

if [ $attempt -eq $max_attempts ]; then
  echo "FAILED: API server did not become healthy"
  exit 1
fi

# Give Valkey a moment to stabilize
sleep 2

# Login to get a token
echo "Logging in as $ADMIN_USER..."
token=$(curl -sf -X POST "$API_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])" 2>/dev/null)

if [ -z "$token" ]; then
  echo "FAILED: Could not obtain token"
  exit 1
fi

echo "Got token: ${token:0:20}..."

# Make requests until we hit the rate limit
echo "Making requests to trigger 429..."
request_count=0
status=""
response_headers=""

while [ "$status" != "429" ]; do
  request_count=$((request_count + 1))
  if [ $request_count -gt 10 ]; then
    echo "FAILED: Did not receive 429 after 10 requests (capacity=$TIGHT_CAPACITY)"
    exit 1
  fi

  # Make request and capture full response
  response=$(curl -sS -i -X GET "$API_URL/api/v1/customers" \
    -H "Authorization: Bearer $token" \
    -H "Accept: application/vnd.api+json" 2>&1)

  # Parse status from response
  status=$(echo "$response" | head -1 | grep -oP 'HTTP/[0-9.]+\s+\K[0-9]+' || echo "")

  echo "Request $request_count: HTTP $status"

  if [ "$status" = "429" ]; then
    response_headers="$response"
  fi
done

# Validate 429 response
echo ""
echo "Got 429 response! Validating rate-limit headers..."

# Extract headers
x_ratelimit_limit=$(echo "$response_headers" | grep -i "^X-RateLimit-Limit:" || echo "")
x_ratelimit_remaining=$(echo "$response_headers" | grep -i "^X-RateLimit-Remaining:" || echo "")
retry_after=$(echo "$response_headers" | grep -i "^Retry-After:" || echo "")

echo "Response headers:"
echo "$response_headers" | grep -E "^(HTTP|X-RateLimit|Retry-After):" || true

if [ -z "$x_ratelimit_limit" ] && [ -z "$retry_after" ]; then
  echo "FAILED: Missing rate-limit headers"
  exit 1
fi

echo ""
echo "✓ 429 response received"
if [ -n "$x_ratelimit_limit" ]; then
  echo "✓ $x_ratelimit_limit"
fi
if [ -n "$x_ratelimit_remaining" ]; then
  echo "✓ $x_ratelimit_remaining"
fi
if [ -n "$retry_after" ]; then
  echo "✓ $retry_after"
fi

# Restore default limit
echo ""
echo "Restoring api-server with default limit..."
docker compose up -d --force-recreate api-server

# Wait for restore
echo "Waiting for api-server to become healthy..."
attempt=0
while [ $attempt -lt $max_attempts ]; do
  if docker compose exec api-server wget -q --tries=1 --spider http://localhost:8080/actuator/health 2>/dev/null; then
    echo "API server restored to default limit"
    break
  fi
  attempt=$((attempt + 1))
  sleep 2
done

if [ $attempt -eq $max_attempts ]; then
  echo "WARNING: API server did not become healthy after restore"
fi

echo ""
echo "PASS: Rate-limit demonstration works end-to-end"
