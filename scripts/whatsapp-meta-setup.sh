#!/usr/bin/env bash
# Palmart ↔ Meta WhatsApp Cloud API — copy-paste setup helper.
# Run: ./backend/scripts/whatsapp-meta-setup.sh
set -euo pipefail

META_APP_ID="1296958775359186"
META_APP_NAME="Kiosk"
API_BASE="${API_PUBLIC_BASE_URL:-https://kiosk.zelisline.com}"
PHONE_NUMBER_ID="${WHATSAPP_META_PHONE_NUMBER_ID:-1252977897893339}"
GRAPH_VERSION="${WHATSAPP_META_GRAPH_VERSION:-v25.0}"

if [[ -z "${WHATSAPP_META_WEBHOOK_VERIFY_TOKEN:-}" ]]; then
  WHATSAPP_META_WEBHOOK_VERIFY_TOKEN="$(openssl rand -hex 16)"
fi

WEBHOOK_URL="${API_BASE}/webhooks/whatsapp"

cat <<EOF

================================================================================
Palmart WhatsApp (Meta Cloud API) — setup values
================================================================================

Meta app: ${META_APP_NAME}  (App ID: ${META_APP_ID})
API base: ${API_BASE}

--- 1) Meta Developers (add WhatsApp product) ---
  https://developers.facebook.com/apps/${META_APP_ID}/dashboard/
  → Products → Add Product → WhatsApp → Set up
  → If WhatsApp is missing: App settings → Basic → connect Business portfolio, save, retry.

--- 2) Meta WhatsApp → Configuration (webhook) ---
  Callback URL:     ${WEBHOOK_URL}
  Verify token:     ${WHATSAPP_META_WEBHOOK_VERIFY_TOKEN}
  Subscribe to:     messages (and message_template_status_update if offered)

--- 3) Meta WhatsApp → API Setup ---
  Phone number ID:  ${PHONE_NUMBER_ID}
  (Copy from API Setup if different from above.)

--- 4) System User token (permanent, not 24h dev token) ---
  business.facebook.com → Business settings → System users
  → Assign WABA + phone → Generate token for app "${META_APP_NAME}"
  → Scopes: whatsapp_business_messaging, whatsapp_business_management

--- 5) App secret (for inbound webhook signature) ---
  https://developers.facebook.com/apps/${META_APP_ID}/settings/basic/
  → Show App secret

--- 6) Palmart Super Admin → Platform integrations → Meta WhatsApp ---
  Access token:           <paste System User token>
  Phone number ID:        ${PHONE_NUMBER_ID}
  Graph API version:      ${GRAPH_VERSION}
  Webhook verify token:   ${WHATSAPP_META_WEBHOOK_VERIFY_TOKEN}
  App secret:             <paste App secret>

--- 7) Optional server env (fallback if DB empty; prefer Super Admin) ---
  API_PUBLIC_BASE_URL=${API_BASE}
  WHATSAPP_META_PHONE_NUMBER_ID=${PHONE_NUMBER_ID}
  WHATSAPP_META_GRAPH_VERSION=${GRAPH_VERSION}
  WHATSAPP_META_WEBHOOK_VERIFY_TOKEN=${WHATSAPP_META_WEBHOOK_VERIFY_TOKEN}
  WHATSAPP_META_ACCESS_TOKEN=<token>
  WHATSAPP_META_APP_SECRET=<secret>

--- 8) Verify in Palmart ---
  Customers → messaging → Send test WhatsApp
  → "Why do cold numbers fail?" (templates must include payment_reminder APPROVED)

--- 9) Templates (WhatsApp Manager) ---
  Required for cold/test sends: payment_reminder, credit_sale_receipt (APPROVED)

================================================================================
Export for this shell session:
  export WHATSAPP_META_WEBHOOK_VERIFY_TOKEN='${WHATSAPP_META_WEBHOOK_VERIFY_TOKEN}'
================================================================================

EOF
