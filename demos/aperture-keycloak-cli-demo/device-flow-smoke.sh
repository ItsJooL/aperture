#!/usr/bin/env bash
# Proves the OIDC device authorization grant end-to-end without a human in a
# browser: brings the stack up, drives `apkc auth login` in the background,
# and approves the device code the same way a browser would (GET the
# verification URL, submit the Keycloak login form, submit the consent
# form) using curl with a cookie jar. Then asserts the CLI can use the
# stored token against the real API.
#
# This does NOT replace the manual walkthrough in README.md -- watching the
# device flow happen in a browser is the point of this demo. This script
# exists so the flow can be verified in CI / headlessly.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO_DIR="$SCRIPT_DIR"
REPO_ROOT="$(cd "$DEMO_DIR/../.." && pwd)"

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8181}"
API_URL="${API_URL:-http://localhost:8081}"
REALM="${REALM:-aperture}"
CLIENT_ID="${CLIENT_ID:-aperture-cli}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin@keycloak-demo.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Admin123!}"
SKIP_BUILD="${SKIP_BUILD:-0}"
SKIP_DOCKER_DOWN="${SKIP_DOCKER_DOWN:-0}"

WORKDIR="$(mktemp -d /tmp/apkc-device-flow-smoke.XXXXXX)"
LOGIN_OUT="$WORKDIR/login.out"
COOKIE_JAR="$WORKDIR/cookies.txt"
APKC_CONFIG="$WORKDIR/config.json"
export APKC_CONFIG

LOGIN_PID=""

log() { echo "[device-flow-smoke] $*"; }

cleanup() {
    local status=$?
    if [[ -n "$LOGIN_PID" ]] && kill -0 "$LOGIN_PID" 2>/dev/null; then
        kill "$LOGIN_PID" 2>/dev/null || true
        wait "$LOGIN_PID" 2>/dev/null || true
    fi
    if [[ "$SKIP_DOCKER_DOWN" != "1" ]]; then
        log "Tearing down docker compose stack..."
        (cd "$DEMO_DIR" && docker compose down -v) || true
    fi
    rm -rf "$WORKDIR"
    exit "$status"
}
trap cleanup EXIT INT TERM

# --- 1. Bring the stack up and wait for every healthcheck ------------------
log "Starting docker compose stack (Keycloak :8181, API :8081)..."
(cd "$DEMO_DIR" && docker compose up -d --build --wait --wait-timeout 300)
log "Stack is healthy."

# --- 2. Build (or locate) the generated CLI ---------------------------------
CLI_DIR="$DEMO_DIR/target/generated-cli/aperture-cli"
CLI_JAR="$CLI_DIR/target/aperture-cli-0.0.1-SNAPSHOT.jar"
CLI_NATIVE="$CLI_DIR/target/apkc"

if [[ "$SKIP_BUILD" != "1" || ( ! -f "$CLI_JAR" && ! -x "$CLI_NATIVE" ) ]]; then
    log "Building demo API + generated CLI (mvn package)..."
    (cd "$REPO_ROOT" && mise exec -- mvn -q -pl demos/aperture-keycloak-cli-demo -am package -DskipTests)
fi

if [[ -x "$CLI_NATIVE" ]]; then
    log "Using native CLI binary: $CLI_NATIVE"
    APKC=("$CLI_NATIVE")
elif [[ -f "$CLI_JAR" ]]; then
    log "Using CLI jar: $CLI_JAR"
    APKC=(java -jar "$CLI_JAR")
else
    log "ERROR: no generated CLI found at $CLI_NATIVE or $CLI_JAR"
    exit 1
fi

"${APKC[@]}" config set-server "$API_URL" >/dev/null

# --- 3. Start `auth login` in the background and capture the device code ---
log "Starting 'apkc auth login' in the background..."
"${APKC[@]}" auth login --issuer "$KEYCLOAK_URL/realms/$REALM" --client-id "$CLIENT_ID" \
    >"$LOGIN_OUT" 2>&1 &
LOGIN_PID=$!

USER_CODE=""
for _ in $(seq 1 30); do
    if grep -q "^and enter code:" "$LOGIN_OUT" 2>/dev/null; then
        USER_CODE="$(grep "^and enter code:" "$LOGIN_OUT" | awk '{print $NF}')"
        break
    fi
    sleep 1
done
if [[ -z "$USER_CODE" ]]; then
    log "ERROR: CLI never printed a device user code. Output so far:"
    cat "$LOGIN_OUT" || true
    exit 1
fi
log "Device user code: $USER_CODE"

# --- 4. Approve the device code the way a browser would ---------------------
# a) GET the verification URL -> Keycloak's login form.
LOGIN_HTML="$WORKDIR/login-form.html"
curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" -L \
    "$KEYCLOAK_URL/realms/$REALM/device?user_code=$USER_CODE" \
    -o "$LOGIN_HTML"
LOGIN_ACTION="$(grep -o 'id="kc-form-login"[^>]*action="[^"]*"' "$LOGIN_HTML" \
    | sed -E 's/.*action="([^"]*)".*/\1/' | sed 's/&amp;/\&/g')"
if [[ -z "$LOGIN_ACTION" ]]; then
    log "ERROR: could not find the Keycloak login form action. Got:"
    cat "$LOGIN_HTML"
    exit 1
fi

# b) POST username/password -> the OAuth consent (device) page.
CONSENT_HTML="$WORKDIR/consent.html"
curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" -L "$LOGIN_ACTION" \
    --data-urlencode "username=$ADMIN_USERNAME" \
    --data-urlencode "password=$ADMIN_PASSWORD" \
    --data-urlencode "credentialId=" \
    -o "$CONSENT_HTML"
CONSENT_ACTION="$(grep -o 'action="/realms/[^"]*login-actions/consent[^"]*"' "$CONSENT_HTML" \
    | sed -E 's/action="([^"]*)"/\1/' | sed 's/&amp;/\&/g')"
CONSENT_CODE="$(grep -o 'name="code" value="[^"]*"' "$CONSENT_HTML" \
    | sed -E 's/.*value="([^"]*)"/\1/')"
if [[ -z "$CONSENT_ACTION" || -z "$CONSENT_CODE" ]]; then
    log "ERROR: could not find the OAuth consent form. Got:"
    cat "$CONSENT_HTML"
    exit 1
fi

# c) POST accept=Yes -> "Device Login Successful".
STATUS_HTML="$WORKDIR/status.html"
curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" -L "$KEYCLOAK_URL$CONSENT_ACTION" \
    --data-urlencode "code=$CONSENT_CODE" \
    --data-urlencode "accept=Yes" \
    -o "$STATUS_HTML"
if ! grep -qi "Device Login Successful" "$STATUS_HTML"; then
    log "ERROR: device approval did not report success. Got:"
    cat "$STATUS_HTML"
    exit 1
fi
log "Device code approved via curl (username=$ADMIN_USERNAME)."

# --- 5. Wait for the background `auth login` to finish -----------------------
if ! wait "$LOGIN_PID"; then
    log "ERROR: 'apkc auth login' exited non-zero. Output:"
    cat "$LOGIN_OUT"
    exit 1
fi
LOGIN_PID=""
log "CLI login finished:"
sed 's/^/  /' "$LOGIN_OUT"

# --- 6. Prove the stored token actually works --------------------------------
log "Verifying 'apkc auth me'..."
ME_JSON="$("${APKC[@]}" auth me)"
echo "$ME_JSON" | sed 's/^/  /'
if ! echo "$ME_JSON" | grep -q "\"preferred_username\" *: *\"$ADMIN_USERNAME\""; then
    log "ERROR: 'apkc auth me' did not return the expected user."
    exit 1
fi

log "Verifying 'apkc get products'..."
PRODUCTS_OUT="$("${APKC[@]}" get products)"
echo "$PRODUCTS_OUT" | sed 's/^/  /'
if [[ -z "$PRODUCTS_OUT" ]]; then
    log "ERROR: 'apkc get products' returned no output."
    exit 1
fi

log "SUCCESS: device flow completed headlessly and the stored token works."
