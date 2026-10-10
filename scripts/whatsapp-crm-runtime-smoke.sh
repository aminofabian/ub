#!/usr/bin/env bash
# Runtime smoke test for the WhatsApp channel + CRM (M1–M4).
#
# Proves the runtime path the compile/boot checks don't:
#   1. webhook signature verification (rejects bad, accepts good)
#   2. inbound ingest -> contact/conversation/message persistence
#   3. the automation engine (keyword + first-inbound rules)
#   4. the outbox drain -> Meta send + wamid capture (against a local stub)
#   5. broadcast fan-out + per-recipient delivery tracking
#   6. the authenticated inbox read (test-auth headers)
#
# Self-contained and idempotent — starts MariaDB + the app + a stub Meta endpoint if
# needed, resets its own rows, and tears everything down on exit.
#
# Usage:
#   backend/scripts/whatsapp-crm-runtime-smoke.sh
#
# Env overrides:
#   MDB_PORT=33306  MDB_SOCKET=/tmp/ub-mdb.sock  DB_NAME=ub  APP_PORT=5050  STUB_PORT=8799
#   MARIADB_BIN, MARIADBD_BIN, MDB_DATADIR, PYTHON_BIN
#   KEEP_RUNNING=1   # leave everything up on exit (for inspection)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$(dirname "$SCRIPT_DIR")"

MDB_PORT="${MDB_PORT:-33306}"
MDB_SOCKET="${MDB_SOCKET:-/tmp/ub-mdb.sock}"
DB_NAME="${DB_NAME:-ub}"
APP_PORT="${APP_PORT:-5050}"
STUB_PORT="${STUB_PORT:-8799}"
MARIADB_BIN="${MARIADB_BIN:-/opt/homebrew/bin/mariadb}"
MARIADBD_BIN="${MARIADBD_BIN:-/opt/homebrew/bin/mariadbd}"
MDB_DATADIR="${MDB_DATADIR:-/opt/homebrew/var/mysql}"
PYTHON_BIN="${PYTHON_BIN:-python3}"

BIZ_ID="11111111-1111-1111-1111-111111111111"
ROUTE_ID="22222222-2222-2222-2222-222222222222"
AUTO_KEYWORD_ID="44444444-4444-4444-4444-444444444444"
AUTO_WELCOME_ID="55555555-5555-5555-5555-555555555555"
OWNER_ROLE_ID="22222222-0000-0000-0000-000000000001"
TEST_USER_ID="33333333-3333-3333-3333-333333333333"
PHONE_NUMBER_ID="999000111222333"
# Deliberately different from PHONE_NUMBER_ID so the assertion proves the routed number was used.
PLATFORM_PHONE_NUMBER_ID="111000111000111"
CUSTOMER_PHONE="254712345678"
APP_SECRET="smoke-app-secret"
WAMID="wamid.SMOKE.$(date +%s)"

APP_LOG="/tmp/whatsapp-crm-smoke-app.log"
MDB_LOG="/tmp/whatsapp-crm-smoke-mdb.log"
STUB_LOG="/tmp/whatsapp-crm-smoke-stub.log"
STUB_PATHS="/tmp/whatsapp-crm-smoke-stub-paths.log"
STUB_PY="/tmp/whatsapp-crm-smoke-stub.py"
PAYLOAD="/tmp/whatsapp-crm-smoke-payload.json"

STARTED_MDB=0
STARTED_APP=0
STARTED_STUB=0
APP_PID=""
STUB_PID=""

db() { "$MARIADB_BIN" --socket="$MDB_SOCKET" -uroot "$DB_NAME" -N -B -e "$1"; }
fail() { echo "✗ FAIL: $*" >&2; [[ -f "$APP_LOG" ]] && echo "  (app log: $APP_LOG)"; exit 1; }
ok() { echo "  ✓ $*"; }

wait_for() { # <sql> <expected> <label>
  local want="$2" label="$3" got=""
  for _ in $(seq 1 40); do
    got="$(db "$1")"
    [[ "$got" == "$want" ]] && { ok "$label ($got)"; return 0; }
    sleep 1
  done
  fail "$label: expected '$want', got '$got'"
}

cleanup() {
  if [[ "${KEEP_RUNNING:-0}" == "1" ]]; then
    echo "── KEEP_RUNNING=1 → leaving app/stub/MariaDB up ──"
    return
  fi
  [[ -n "$APP_PID" && "$STARTED_APP" == "1" ]] && kill "$APP_PID" 2>/dev/null || true
  [[ -n "$STUB_PID" && "$STARTED_STUB" == "1" ]] && kill "$STUB_PID" 2>/dev/null || true
  if [[ "$STARTED_MDB" == "1" ]]; then
    "$MARIADB_BIN" --socket="$MDB_SOCKET" -uroot -e "SHUTDOWN" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# ── 1. MariaDB ──────────────────────────────────────────────────────────────
if lsof -nP -iTCP:"$MDB_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "── MariaDB already listening on :$MDB_PORT ──"
else
  echo "── starting MariaDB on :$MDB_PORT (skip-grant-tables) ──"
  nohup "$MARIADBD_BIN" --datadir="$MDB_DATADIR" --port="$MDB_PORT" --socket="$MDB_SOCKET" \
    --bind-address=127.0.0.1 --skip-grant-tables > "$MDB_LOG" 2>&1 &
  STARTED_MDB=1
  for _ in $(seq 1 30); do lsof -nP -iTCP:"$MDB_PORT" -sTCP:LISTEN >/dev/null 2>&1 && break; sleep 1; done
fi
lsof -nP -iTCP:"$MDB_PORT" -sTCP:LISTEN >/dev/null 2>&1 || fail "MariaDB not listening on :$MDB_PORT"
"$MARIADB_BIN" --socket="$MDB_SOCKET" -uroot \
  -e "CREATE DATABASE IF NOT EXISTS $DB_NAME CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# ── 2. Local Meta Graph stub ────────────────────────────────────────────────
echo "── starting local Meta Graph stub on :$STUB_PORT ──"
rm -f "$STUB_PATHS"
cat > "$STUB_PY" <<'PY'
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1])
PATH_LOG = sys.argv[2]

class Handler(BaseHTTPRequestHandler):
    counter = 0

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        self.rfile.read(length)
        with open(PATH_LOG, "a") as fh:
            fh.write(self.path + " " + (self.headers.get("Authorization") or "") + "\n")
        Handler.counter += 1
        body = json.dumps({
            "messaging_product": "whatsapp",
            "contacts": [{"wa_id": "254700000000"}],
            "messages": [{"id": "wamid.MOCK.%d" % Handler.counter}],
        }).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass

HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
PY
nohup "$PYTHON_BIN" -u "$STUB_PY" "$STUB_PORT" "$STUB_PATHS" > "$STUB_LOG" 2>&1 &
STUB_PID=$!
STARTED_STUB=1
for _ in $(seq 1 20); do lsof -nP -iTCP:"$STUB_PORT" -sTCP:LISTEN >/dev/null 2>&1 && break; sleep 1; done
lsof -nP -iTCP:"$STUB_PORT" -sTCP:LISTEN >/dev/null 2>&1 || fail "stub not listening on :$STUB_PORT"
ok "stub up"

# ── 3. Boot the backend (channel + outbox + test-auth on) ───────────────────
echo "── booting backend ──"
: > "$APP_LOG"
SPRING_DATASOURCE_URL="jdbc:mariadb://127.0.0.1:${MDB_PORT}/${DB_NAME}?connectionCollation=utf8mb4_unicode_ci" \
SPRING_DATASOURCE_USERNAME="ub_local" \
SPRING_DATASOURCE_DRIVER_CLASS_NAME="org.mariadb.jdbc.Driver" \
SPRING_JPA_PROPERTIES_HIBERNATE_DIALECT="org.hibernate.dialect.MariaDBDialect" \
APP_INTEGRATIONS_WHATSAPP_ENABLED="true" \
APP_INTEGRATIONS_WHATSAPP_OUTBOX_ENABLED="true" \
APP_INTEGRATIONS_WHATSAPP_OUTBOX_FIXED_DELAY_MS="2000" \
APP_INTEGRATIONS_WHATSAPP_OUTBOX_INITIAL_DELAY_MS="1000" \
APP_INTEGRATIONS_WHATSAPP_SEND_GRAPH_BASE_URL="http://127.0.0.1:${STUB_PORT}" \
WHATSAPP_META_ACCESS_TOKEN="smoke-token" \
WHATSAPP_META_PHONE_NUMBER_ID="$PLATFORM_PHONE_NUMBER_ID" \
WHATSAPP_META_APP_SECRET="$APP_SECRET" \
WHATSAPP_META_GRAPH_VERSION="v25.0" \
APP_SECURITY_TEST_AUTH_ENABLED="true" \
nohup "$BACKEND_DIR/gradlew" -p "$BACKEND_DIR" bootRun --console=plain --offline > "$APP_LOG" 2>&1 &
APP_PID=$!
STARTED_APP=1

for _ in $(seq 1 150); do
  grep -q "Started UbApplication" "$APP_LOG" 2>/dev/null && break
  if grep -qE "APPLICATION FAILED TO START|BUILD FAILED" "$APP_LOG" 2>/dev/null; then
    tail -40 "$APP_LOG"; fail "app failed to start"
  fi
  sleep 2
done
grep -q "Started UbApplication" "$APP_LOG" || { tail -40 "$APP_LOG"; fail "app did not start within timeout"; }
ok "app up on :$APP_PORT"

# ── 4. Seed shop + route + automation rules ─────────────────────────────────
echo "── seeding ──"
"$MARIADB_BIN" --socket="$MDB_SOCKET" -uroot "$DB_NAME" <<SQL
DELETE FROM crm_automation_run WHERE business_id='$BIZ_ID';
DELETE FROM crm_automation_step WHERE automation_id IN ('$AUTO_KEYWORD_ID','$AUTO_WELCOME_ID');
DELETE FROM crm_automation WHERE business_id='$BIZ_ID';
DELETE FROM crm_broadcast_recipient WHERE business_id='$BIZ_ID';
DELETE FROM crm_broadcast WHERE business_id='$BIZ_ID';
DELETE FROM crm_outbound WHERE business_id='$BIZ_ID';
DELETE FROM crm_message WHERE business_id='$BIZ_ID';
DELETE FROM crm_note WHERE business_id='$BIZ_ID';
DELETE FROM crm_conversation WHERE business_id='$BIZ_ID';
DELETE FROM crm_contact WHERE business_id='$BIZ_ID';
DELETE FROM crm_webhook_event WHERE business_id='$BIZ_ID';
DELETE FROM whatsapp_channel_route WHERE business_id='$BIZ_ID';
DELETE FROM businesses WHERE id='$BIZ_ID';

INSERT INTO businesses (id, name, slug, settings) VALUES ('$BIZ_ID', 'Smoke Shop', 'smoke-shop', '{}');
INSERT INTO whatsapp_channel_route (id, phone_number_id, business_id, display_number, label, status)
VALUES ('$ROUTE_ID', '$PHONE_NUMBER_ID', '$BIZ_ID', '+254700000000', 'Smoke', 'active');

INSERT INTO crm_automation (id, business_id, name, trigger_type, trigger_config, active, execution_count)
VALUES ('$AUTO_KEYWORD_ID', '$BIZ_ID', 'Price question', 'KEYWORD_MATCH', '{"keywords":["price"]}', 1, 0);
INSERT INTO crm_automation_step (id, automation_id, position, step_type, step_config)
VALUES ('66666666-6666-6666-6666-666666666661', '$AUTO_KEYWORD_ID', 0, 'SEND_MESSAGE', '{"body":"Prices start at KES 100."}');

INSERT INTO crm_automation (id, business_id, name, trigger_type, trigger_config, active, execution_count)
VALUES ('$AUTO_WELCOME_ID', '$BIZ_ID', 'Welcome', 'FIRST_INBOUND_MESSAGE', '{}', 1, 0);
INSERT INTO crm_automation_step (id, automation_id, position, step_type, step_config)
VALUES ('66666666-6666-6666-6666-666666666662', '$AUTO_WELCOME_ID', 0, 'ADD_TAG', '{"tag":"new"}');
SQL
ok "seeded"

# ── Tenant brings its own Meta app (Model B) via the credit-settings API ─────
TENANT_APP_SECRET="tenant-app-secret"
TENANT_TOKEN="tenant-access-token"
TENANT_VERIFY="tenant-verify-token"
CRED_HTTP=$(curl -sS -o /tmp/whatsapp-crm-smoke-credits.txt -w '%{http_code}' \
  -X PUT "http://127.0.0.1:${APP_PORT}/api/v1/credits/sale-reminder-settings" \
  -H "Content-Type: application/json" \
  -H "X-Test-User-Id: $TEST_USER_ID" -H "X-Test-Role-Id: $OWNER_ROLE_ID" -H "X-Tenant-Id: $BIZ_ID" \
  --data "{\"enabled\":false,\"paymentAccountUrl\":\"https://example.com\",\"whatsappMetaPhoneNumberId\":\"$PHONE_NUMBER_ID\",\"whatsappMetaAccessToken\":\"$TENANT_TOKEN\",\"whatsappMetaGraphVersion\":\"v25.0\",\"whatsappMetaAppSecret\":\"$TENANT_APP_SECRET\",\"whatsappMetaWebhookVerifyToken\":\"$TENANT_VERIFY\"}")
[[ "$CRED_HTTP" == "200" ]] || { cat /tmp/whatsapp-crm-smoke-credits.txt; fail "credit-settings PUT should be 200, got $CRED_HTTP"; }
ok "tenant Meta credentials saved (own Meta app)"
wait_for "SELECT (own_credentials = 1) FROM whatsapp_channel_route WHERE phone_number_id='$PHONE_NUMBER_ID'" "1" "route adopted own credentials"

# ── 5. Webhook signature: reject bad, accept good ───────────────────────────
echo "── webhook signature ──"
cat > "$PAYLOAD" <<JSON
{
  "object": "whatsapp_business_account",
  "entry": [{
    "id": "WABA_SMOKE",
    "changes": [{
      "field": "messages",
      "value": {
        "messaging_product": "whatsapp",
        "metadata": { "display_phone_number": "254700000000", "phone_number_id": "$PHONE_NUMBER_ID" },
        "contacts": [{ "profile": { "name": "Smoke Shopper" }, "wa_id": "$CUSTOMER_PHONE" }],
        "messages": [{
          "from": "$CUSTOMER_PHONE",
          "id": "$WAMID",
          "timestamp": "1700000000",
          "type": "text",
          "text": { "body": "hi, what is the price?" }
        }]
      }
    }]
  }]
}
JSON
BODY="$(cat "$PAYLOAD")"

BAD=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:${APP_PORT}/webhooks/whatsapp" \
  -H "Content-Type: application/json" -H "X-Hub-Signature-256: sha256=deadbeef" --data-binary "$BODY")
[[ "$BAD" == "403" ]] || fail "bad signature should be 403, got $BAD"
ok "bad signature rejected (403)"

SIG="$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$TENANT_APP_SECRET" | awk '{print $NF}')"
GOOD=$(curl -sS -o /tmp/whatsapp-crm-smoke-resp.txt -w '%{http_code}' -X POST "http://127.0.0.1:${APP_PORT}/webhooks/whatsapp" \
  -H "Content-Type: application/json" -H "X-Hub-Signature-256: sha256=$SIG" --data-binary "$BODY")
[[ "$GOOD" == "200" ]] || fail "good signature (tenant app secret) should be 200, got $GOOD"
grep -q "EVENT_RECEIVED" /tmp/whatsapp-crm-smoke-resp.txt || fail "webhook body was not EVENT_RECEIVED"
ok "valid signature accepted (tenant app secret → 200)"

# ── 6. Ingest + automation assertions ───────────────────────────────────────
echo "── ingest + automation ──"
sleep 2
wait_for "SELECT COUNT(*) FROM crm_webhook_event WHERE wamid='$WAMID'" "1" "crm_webhook_event recorded"
wait_for "SELECT COUNT(*) FROM crm_contact WHERE business_id='$BIZ_ID' AND phone_e164='$CUSTOMER_PHONE'" "1" "crm_contact created"
wait_for "SELECT COUNT(*) FROM crm_conversation WHERE business_id='$BIZ_ID' AND status='open'" "1" "crm_conversation created"
wait_for "SELECT COUNT(*) FROM crm_message WHERE business_id='$BIZ_ID' AND direction='inbound'" "1" "crm_message persisted"
wait_for "SELECT COUNT(*) FROM crm_automation_run WHERE business_id='$BIZ_ID' AND status='success'" "2" "both automations fired"
wait_for "SELECT COUNT(*) FROM crm_contact WHERE business_id='$BIZ_ID' AND tags_json LIKE '%new%'" "1" "welcome rule tagged the contact"

# ── 7. Broadcast fan-out (free-form, all contacts with an open window) ──────
echo "── broadcast ──"
HTTP=$(curl -sS -o /tmp/whatsapp-crm-smoke-broadcast.txt -w '%{http_code}' \
  -X POST "http://127.0.0.1:${APP_PORT}/api/v1/crm/broadcasts" \
  -H "Content-Type: application/json" \
  -H "X-Test-User-Id: $TEST_USER_ID" -H "X-Test-Role-Id: $OWNER_ROLE_ID" -H "X-Tenant-Id: $BIZ_ID" \
  --data '{"name":"Smoke blast","mode":"free_form","body":"Hello from the smoke test","audience":{"type":"all"}}')
[[ "$HTTP" == "201" ]] || { cat /tmp/whatsapp-crm-smoke-broadcast.txt; fail "broadcast create should be 201, got $HTTP"; }
ok "broadcast created (201)"
wait_for "SELECT COUNT(*) FROM crm_broadcast WHERE business_id='$BIZ_ID'" "1" "crm_broadcast row"
wait_for "SELECT COUNT(*) FROM crm_broadcast_recipient WHERE business_id='$BIZ_ID'" "1" "crm_broadcast_recipient row"
BC_ID="$(db "SELECT id FROM crm_broadcast WHERE business_id='$BIZ_ID' ORDER BY created_at DESC LIMIT 1")"

# ── 8. Outbox drain -> Meta stub -> wamid captured ──────────────────────────
echo "── outbox drain ──"
wait_for "SELECT COUNT(*) FROM crm_outbound WHERE business_id='$BIZ_ID' AND status='sent'" "2" "both outbound rows sent (automation reply + broadcast)"
wait_for "SELECT COUNT(*) FROM crm_message WHERE business_id='$BIZ_ID' AND direction='outbound' AND status='sent' AND wa_message_id LIKE 'wamid.MOCK.%'" "1" "automation reply sent with wamid"
wait_for "SELECT status FROM crm_broadcast_recipient WHERE broadcast_id='$BC_ID'" "sent" "broadcast recipient marked sent"
wait_for "SELECT (wa_message_id LIKE 'wamid.MOCK.%') FROM crm_broadcast_recipient WHERE broadcast_id='$BC_ID'" "1" "broadcast recipient captured wamid"
grep -q "/$PHONE_NUMBER_ID/messages" "$STUB_PATHS" \
  || fail "outbound did not use the routed number (paths: $(cat "$STUB_PATHS" 2>/dev/null))"
ok "outbound sent from the shop's routed number ($PHONE_NUMBER_ID)"
grep -q "Bearer $TENANT_TOKEN" "$STUB_PATHS" \
  || fail "outbound did not use the tenant token (stub saw: $(cat "$STUB_PATHS" 2>/dev/null))"
ok "outbound used the tenant's own access token (Model B)"

# ── 9. Authenticated inbox read ─────────────────────────────────────────────
echo "── inbox read ──"
INBOX=$(curl -sS "http://127.0.0.1:${APP_PORT}/api/v1/crm/conversations?status=open" \
  -H "X-Test-User-Id: $TEST_USER_ID" -H "X-Test-Role-Id: $OWNER_ROLE_ID" -H "X-Tenant-Id: $BIZ_ID")
echo "$INBOX" | grep -q "$CUSTOMER_PHONE" || fail "inbox read did not include the contact (resp: $INBOX)"
echo "$INBOX" | grep -q '"total":1' || fail "inbox read total != 1 (resp: $INBOX)"
ok "inbox read returned the conversation"

echo ""
echo "==================================================================="
echo "  WhatsApp CRM runtime smoke test: PASS"
echo "==================================================================="
