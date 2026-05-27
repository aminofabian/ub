#!/usr/bin/env bash
# ── CI smoke test: all Flyway migrations against MariaDB 10.11 ──
# See DESKTOP_INSTALLATION.md §7.
#
# Usage:
#   ./scripts/ci-smoke-test-mariadb.sh        # uses default password
#   MARIADB_ROOT_PASSWORD=secret ./scripts/ci-smoke-test-mariadb.sh
#
# Requires: docker, java 21, the Gradle wrapper.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$(dirname "$SCRIPT_DIR")"
cd "$BACKEND_DIR"

DB_CONTAINER="palmart-mariadb-smoke-$(date +%s)"
DB_PORT="${MARIADB_PORT:-33306}"
DB_USER="${MARIADB_USER:-ub_local}"
DB_PASS="${MARIADB_PASSWORD:-smoketest123}"
DB_NAME="ub"
ROOT_PASS="${MARIADB_ROOT_PASSWORD:-rootsecret123}"

cleanup() {
  echo ""
  echo "── Cleaning up MariaDB container ${DB_CONTAINER} ──"
  docker rm -f "$DB_CONTAINER" 2>/dev/null || true
}
trap cleanup EXIT

echo "── Starting MariaDB 10.11 container ──"
docker run -d \
  --name "$DB_CONTAINER" \
  -e MARIADB_ROOT_PASSWORD="$ROOT_PASS" \
  -e MARIADB_DATABASE="$DB_NAME" \
  -e MARIADB_USER="$DB_USER" \
  -e MARIADB_PASSWORD="$DB_PASS" \
  -p "$DB_PORT:3306" \
  mariadb:10.11

echo "── Waiting for MariaDB to be ready ──"
for i in $(seq 1 60); do
  if docker exec "$DB_CONTAINER" mariadb-admin ping -u root -p"$ROOT_PASS" --silent 2>/dev/null; then
    echo "MariaDB is ready after ${i}s."
    break
  fi
  if [ "$i" -eq 60 ]; then
    echo "ERROR: MariaDB did not become ready within 60s."
    docker logs "$DB_CONTAINER" --tail 30
    exit 1
  fi
  sleep 1
done

echo ""
echo "── Running Spring Boot with desktop profile (Flyway runs automatically) ──"

# Spring Boot auto-runs Flyway on startup. We boot the app with the desktop
# profile pointed at the MariaDB container; a successful startup means all
# 113+ Flyway migrations ran cleanly.
#
# The app is killed as soon as we see the "Started UbApplication" log line
# (or after a 120s timeout).
export APP_DESKTOP_DB_PORT="$DB_PORT"
export APP_DESKTOP_DB_USER="$DB_USER"
export APP_DESKTOP_DB_PASSWORD="$DB_PASS"

STARTUP_LOG="$(mktemp)"
SPRING_DATASOURCE_URL="jdbc:mariadb://127.0.0.1:${DB_PORT}/${DB_NAME}" \
SPRING_DATASOURCE_USERNAME="$DB_USER" \
SPRING_DATASOURCE_PASSWORD="$DB_PASS" \
SPRING_PROFILES_ACTIVE="desktop" \
APP_DESKTOP_BUSINESS_ID="00000000-0000-0000-0000-000000000001" \
  timeout 120 ./gradlew bootRun --no-daemon -Pdesktop=true > "$STARTUP_LOG" 2>&1 &
BOOT_PID=$!

# Wait for the "Started" line or timeout
STARTED=0
for i in $(seq 1 120); do
  if grep -q "Started UbApplication" "$STARTUP_LOG" 2>/dev/null; then
    echo "Spring Boot started after ${i}s — Flyway migrations succeeded."
    STARTED=1
    break
  fi
  if ! kill -0 "$BOOT_PID" 2>/dev/null; then
    break
  fi
  sleep 1
done

# Always kill the bootRun process
kill "$BOOT_PID" 2>/dev/null || true
wait "$BOOT_PID" 2>/dev/null || true

echo ""
echo "── Startup log (last 40 lines) ──"
tail -40 "$STARTUP_LOG"
rm -f "$STARTUP_LOG"

echo ""
if [ "$STARTED" -eq 1 ]; then
  echo "✅ All Flyway migrations ran successfully against MariaDB 10.11."
else
  echo "❌ Spring Boot did not start — Flyway migrations may have failed against MariaDB 10.11."
  exit 1
fi
