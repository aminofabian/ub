#!/usr/bin/env bash
# Ensures Java 21+ is available. On macOS, installs Temurin 21 via Homebrew if missing.
set -euo pipefail

REQUIRED=21

current_major() {
  command -v java >/dev/null 2>&1 || return 1
  java -version 2>&1 | awk -F[\".] '/version/ {print $2; exit}'
}

major="$(current_major || true)"
if [[ -n "${major:-}" ]] && (( major >= REQUIRED )); then
  echo "Java ${REQUIRED}+ already available."
  java -version
  exit 0
fi

if [[ "$(uname -s)" != "Darwin" ]] || ! command -v brew >/dev/null 2>&1; then
  echo "Java ${REQUIRED}+ is required."
  echo "Install a JDK ${REQUIRED} (e.g. https://adoptium.net) and re-run."
  exit 1
fi

echo "Installing Temurin ${REQUIRED} via Homebrew..."
brew install --cask "temurin@${REQUIRED}"

JAVA_HOME="$(/usr/libexec/java_home -v "${REQUIRED}")"
echo
echo "Java ${REQUIRED} installed at: ${JAVA_HOME}"
echo "Add to your shell profile if needed:"
echo "  export JAVA_HOME=\"\$(/usr/libexec/java_home -v ${REQUIRED})\""
echo "  export PATH=\"\$JAVA_HOME/bin:\$PATH\""
