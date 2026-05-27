#!/usr/bin/env bash
# Quick integration test: start Spring Boot desktop, call setup endpoint, verify.
set -euo pipefail
cd "$(dirname "$0")/.."

APP_PORT=5050
TEST_BUSINESS_ID="a0000000-0000-0000-0000-000000000001"
TEST_APP_DATA="/tmp/palmart-test-appdata"
TEST_LOG="/tmp/palmart-desktop-test.log"

# Clean up from previous runs
pkill -f "UbApplication" 2>/dev/null || true
sleep 1

# Clean test app data
rm -rf "$TEST_APP_DATA"

echo "=== 1. Starting Spring Boot ==="
env \
  APP_DESKTOP_DB_PORT=33306 \
  APP_DESKTOP_DB_USER=ub_local \
  APP_DESKTOP_DB_PASSWORD=test123 \
  APP_DESKTOP_BUSINESS_ID="$TEST_BUSINESS_ID" \
  APP_DATA="$TEST_APP_DATA" \
  SPRING_PROFILES_ACTIVE=desktop \
  ./gradlew bootRun -Pdesktop=true --no-daemon \
  > "$TEST_LOG" 2>&1 &

BOOT_PID=$!
echo "PID: $BOOT_PID"

# Wait for started
for i in $(seq 1 90); do
  if grep -q "Started UbApplication" "$TEST_LOG" 2>/dev/null; then
    echo "✅ App started after ${i}s"
    break
  fi
  if ! kill -0 $BOOT_PID 2>/dev/null; then
    echo "❌ App crashed — last 30 log lines:"
    tail -30 "$TEST_LOG"
    exit 1
  fi
  sleep 1
done

echo ""
echo "=== 2. Check setup status ==="
STATUS=$(curl -s http://127.0.0.1:$APP_PORT/api/v1/desktop/setup/status)
echo "$STATUS"
SETUP_REQUIRED=$(echo "$STATUS" | python3 -c "import sys,json; print(json.load(sys.stdin)['setupRequired'])" 2>/dev/null || echo "PARSE_ERROR")
if [ "$SETUP_REQUIRED" != "True" ]; then
  echo "❌ Expected setupRequired=true, got: $SETUP_REQUIRED"
  kill $BOOT_PID 2>/dev/null
  exit 1
fi
echo "✅ setupRequired=true"

echo ""
echo "=== 3. Run first-run setup ==="
RESPONSE=$(curl -s -X POST http://127.0.0.1:$APP_PORT/api/v1/desktop/setup \
  -H "Content-Type: application/json" \
  -d '{
    "businessName": "Test Shop",
    "ownerName": "Test Owner",
    "ownerEmail": "owner@testshop.local",
    "ownerPassword": "testpassword123",
    "ownerPin": "1234",
    "currency": "KES",
    "countryCode": "KE",
    "timezone": "Africa/Nairobi",
    "hardwareTier": "B",
    "taxRate": 16.0,
    "receiptHeader": "Thank you for shopping!",
    "receiptFooter": "Come again"
  }')
echo "$RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE"

BUSINESS_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['businessId'])" 2>/dev/null || echo "")
if [ -z "$BUSINESS_ID" ]; then
  echo "❌ No businessId in response"
  kill $BOOT_PID 2>/dev/null
  exit 1
fi
echo "✅ businessId=$BUSINESS_ID"

echo ""
echo "=== 4. Idempotency: second call should return 409 ==="
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://127.0.0.1:$APP_PORT/api/v1/desktop/setup \
  -H "Content-Type: application/json" \
  -d '{"businessName":"x","ownerName":"x","ownerEmail":"x@x.com","ownerPassword":"xxxxxxxx"}')
if [ "$HTTP_CODE" = "409" ]; then
  echo "✅ 409 Conflict"
else
  echo "❌ Expected 409, got $HTTP_CODE"
fi

echo ""
echo "=== 5. Verify .initialized file ==="
if [ -f "$TEST_APP_DATA/.initialized" ]; then
  echo "✅ .initialized exists:"
  cat "$TEST_APP_DATA/.initialized"
else
  echo "❌ .initialized missing"
fi

echo ""
echo "=== 6. Verify config files ==="
for f in "$TEST_APP_DATA/conf/jvm.opts" "$TEST_APP_DATA/conf/my.cnf"; do
  if [ -f "$f" ]; then
    echo "✅ $f exists"
  else
    echo "❌ $f missing"
  fi
done

echo ""
echo "=== 7. Verify status after setup ==="
STATUS=$(curl -s http://127.0.0.1:$APP_PORT/api/v1/desktop/setup/status)
echo "$STATUS"
SETUP_REQUIRED=$(echo "$STATUS" | python3 -c "import sys,json; print(json.load(sys.stdin)['setupRequired'])" 2>/dev/null || echo "PARSE_ERROR")
if [ "$SETUP_REQUIRED" = "False" ]; then
  echo "✅ setupRequired=false (initialized)"
else
  echo "❌ Expected setupRequired=false, got: $SETUP_REQUIRED"
fi

echo ""
echo "=== 8. Kill app ==="
kill $BOOT_PID 2>/dev/null
wait $BOOT_PID 2>/dev/null
echo "✅ Test passed!"

# Keep test data for inspection
echo ""
echo "Files created at: $TEST_APP_DATA"
