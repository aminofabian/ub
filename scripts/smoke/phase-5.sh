#!/usr/bin/env bash
# Phase 5 smoke — exercises the customer/wallet/loyalty/claim happy path against a running
# backend. Payments (M-Pesa STK) are intentionally skipped here; the wallet credit step uses
# the cash counter top-up endpoint, which is the channel that Phase 5 actually ships.
#
# Usage:
#   API=http://localhost:5050 \
#   TENANT_ID=<business-uuid> \
#   USER_ID=<owner-user-uuid> \
#   ROLE_ID=<owner-role-uuid> \
#   bash backend/scripts/smoke/phase-5.sh
#
# Requires: curl, jq.
set -euo pipefail

API="${API:-http://localhost:5050}"
TENANT_ID="${TENANT_ID:?must export TENANT_ID (business id)}"
USER_ID="${USER_ID:?must export USER_ID (owner)}"
ROLE_ID="${ROLE_ID:?must export ROLE_ID (owner)}"

hdr=(
  -H "Content-Type: application/json"
  -H "X-Tenant-Id: ${TENANT_ID}"
  -H "X-Test-User-Id: ${USER_ID}"
  -H "X-Test-Role-Id: ${ROLE_ID}"
)

step() { printf "\n=== %s ===\n" "$1"; }

step "1) Create customer with primary phone + loyalty/credit account"
CUSTOMER_BODY=$(jq -n \
  --arg name "Smoke Buyer" \
  --arg phone "0700000777" \
  '{name: $name, phones: [{phone: $phone, primary: true}], creditLimit: 5000}')
CUSTOMER=$(curl -fsS "${hdr[@]}" -X POST "${API}/api/v1/customers" -d "${CUSTOMER_BODY}")
CUSTOMER_ID=$(echo "$CUSTOMER" | jq -r '.id')
echo "customer_id=${CUSTOMER_ID}"

step "2) Configure loyalty: 1 point / KES, 0.01 KES / point, 50% redeem cap"
curl -fsS "${hdr[@]}" -X PUT "${API}/api/v1/credits/loyalty-settings" \
  -d '{"loyaltyPointsPerKes":1,"loyaltyKesPerPoint":0.01,"loyaltyMaxRedeemBps":5000}' \
  | jq .

step "3) Top up wallet by KES 200 cash"
curl -fsS "${hdr[@]}" -X POST "${API}/api/v1/customers/${CUSTOMER_ID}/wallet/top-ups" \
  -d '{"amount":200}'

step "4) Read credit statement"
curl -fsS "${hdr[@]}" "${API}/api/v1/customers/${CUSTOMER_ID}/credit-statement" | jq .

step "5) Issue a public payment claim token"
ISSUE=$(curl -fsS "${hdr[@]}" -X POST "${API}/api/v1/customers/${CUSTOMER_ID}/payment-claims")
CLAIM_ID=$(echo "$ISSUE" | jq -r '.claimId')
TOKEN=$(echo "$ISSUE" | jq -r '.plaintextToken')
echo "claim_id=${CLAIM_ID}"

step "6) Public (unauth) submit claim with token"
curl -fsS -H "Content-Type: application/json" \
  -X POST "${API}/api/v1/public/credits/payment-claims/${TOKEN}" \
  -d '{"amount":150, "reference":"SMOKE-REF"}'

step "7) Admin approves claim with cash channel"
curl -fsS "${hdr[@]}" -X POST "${API}/api/v1/credits/payment-claims/${CLAIM_ID}/approve" \
  -d '{"channel":"cash"}'

step "8) Double-approve is a silent no-op (idempotency)"
curl -fsS "${hdr[@]}" -X POST "${API}/api/v1/credits/payment-claims/${CLAIM_ID}/approve" \
  -d '{"channel":"cash"}'

step "9) Final statement"
curl -fsS "${hdr[@]}" "${API}/api/v1/customers/${CUSTOMER_ID}/credit-statement" | jq .

echo "OK"
