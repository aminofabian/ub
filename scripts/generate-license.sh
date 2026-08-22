#!/usr/bin/env bash
# ── Palmart Desktop — vendor license tool ────────────────────────────────────
# Generates the Ed25519 key pair and issues signed license tokens for Kiosk
# Desktop installs. Runs the backend's own LicenseService/LicensePayload
# classes so the token bytes are identical to what the till verifies.
#
#   ./gradlew compileJava first (or run this — it compiles if needed).
#
# Usage:
#   bash backend/scripts/generate-license.sh bootstrap [--force]
#       ONE-COMMAND SETUP. Generates a key pair once (idempotent — reuses
#       existing keys), stores it OUTSIDE the repo at
#       $HOME/.palmart-license/ (private.pem + public.pem, chmod 600), bakes
#       the PUBLIC key into application-desktop.properties automatically, and
#       prints the APP_DESKTOP_LICENSE_PRIVATE_KEY line for the cloud — or paste
#       PRIVATE_KEY into Super Admin → Platform → Desktop licenses →
#       "License issuer key" (stored encrypted, no restart needed).
#       --force regenerates the key pair (invalidates previously issued
#       licenses — only do this before any licenses exist).
#
#   bash backend/scripts/generate-license.sh pubkey
#       Prints the stored public key (e.g. to verify the bake / share it).
#
#   bash backend/scripts/generate-license.sh keys
#       Prints a fresh key pair to stdout (manual flow):
#         PUBLIC_KEY=<base64>   → paste into application-desktop.properties
#                                 (app.desktop.license.public-key=…)
#         PRIVATE_KEY=<base64>  → keep secret, never ship. Feed it back to
#                                 `issue` via LICENSE_PRIVATE_KEY or --private-key,
#                                 or save it from the Super Admin console
#                                 (Platform → Desktop licenses → License issuer key).
#
#   LICENSE_PRIVATE_KEY=<base64> \
#   bash backend/scripts/generate-license.sh issue \
#       --business "My Shop" --plan shop --days 365 --fingerprint <machine-id>
#       Prints the license token to send to the customer.
#
#       --business  must match the shop name entered in the first-run wizard
#                   EXACTLY (case-sensitive compare at runtime).
#       --plan      counter | shop | lan
#       --days N    validity in days from now (or --expires <ISO-8601> for an
#                   exact expiry instant, or --perpetual for no expiry).
#       --fingerprint REQUIRED — the till's Machine ID, shown in
#                   Kiosk Desktop → Settings → License (copy button). The
#                   runtime rejects any token whose fingerprint doesn't match
#                   the machine, so a key for shop A cannot be used on shop B.
#
#   LICENSE_PUBLIC_KEY=<base64> \
#   bash backend/scripts/generate-license.sh verify --token <token>
#       Round-trip check: decodes + verifies a token against a public key
#       (the same check the till performs) and prints the payload.
#
# Env: PALMART_LICENSE_KEY_DIR overrides the key store location (default
# $HOME/.palmart-license).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEY_DIR="${PALMART_LICENSE_KEY_DIR:-$HOME/.palmart-license}"
PRIVATE_KEY_FILE="$KEY_DIR/private.pem"
PUBLIC_KEY_FILE="$KEY_DIR/public.pem"
PROPS="$ROOT/src/main/resources/application-desktop.properties"
CP="$ROOT/build/classes/java/main"

usage() {
  sed -n '3,45p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-1}"
}

case "${1:-}" in
  -h|--help|help)
    sed -n '3,45p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
  pubkey)
    # No Java/Gradle needed — just read the stored public key.
    if [ ! -f "$PUBLIC_KEY_FILE" ]; then
      echo "No keys yet — run: bash backend/scripts/generate-license.sh bootstrap" >&2
      exit 1
    fi
    cat "$PUBLIC_KEY_FILE"
    echo
    exit 0
    ;;
esac

# ── ensure the backend classes + runtime deps are compiled ─────────────────
if [ ! -d "$CP" ] || [ -z "$(ls -A "$CP" 2>/dev/null)" ]; then
  echo "  compiling backend classes (first run)…"
  (cd "$ROOT" && ./gradlew compileJava -q)
fi

# Resolve the runtime classpath (Jackson + slf4j for LicenseService) via a
# tiny init script — `dependencies --configuration` prints a tree, not paths.
INIT=/tmp/palmart-cp.gradle
cat > "$INIT" << 'GRADLE'
allprojects {
    tasks.register("printRuntimeClasspath") {
        doLast {
            if (project.name == rootProject.name) {
                println project.sourceSets.main.runtimeClasspath.asPath
            }
        }
    }
}
GRADLE
JARS="$(cd "$ROOT" && ./gradlew -q -I "$INIT" printRuntimeClasspath 2>/dev/null | tr -d '\n')"
CLASSPATH="$CP:$JARS"

# ── tiny CLI compiled against the backend classes ──────────────────────────
HARNESS=/tmp/PalmartLicenseCli.java
cat > "$HARNESS" << 'JAVA'
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import zelisline.ub.desktop.license.LicensePayload;
import zelisline.ub.desktop.license.LicenseService;

public class PalmartLicenseCli {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(2); }
        switch (args[0]) {
            case "keys" -> generateKeys();
            case "writekeys" -> writeKeys(args);
            case "issue" -> issue(args);
            case "verify" -> verify(args);
            default -> { System.err.println("unknown command: " + args[0]); usage(2); }
        }
    }

    private static void generateKeys() {
        var kp = LicenseService.generateKeyPair();
        System.out.println("PUBLIC_KEY=" + LicenseService.encodePublicKey(kp.getPublic()));
        System.out.println("PRIVATE_KEY=" + LicenseService.encodePrivateKey(kp.getPrivate()));
        System.err.println();
        System.err.println("# Prefer the one-command setup:");
        System.err.println("#   bash backend/scripts/generate-license.sh bootstrap");
        System.err.println("# Manual: paste PUBLIC_KEY into application-desktop.properties and");
        System.err.println("# set APP_DESKTOP_LICENSE_PRIVATE_KEY=<PRIVATE_KEY> on the cloud.");
    }

    /** Writes a fresh key pair as private.pem / public.pem into the given dir. */
    private static void writeKeys(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("writekeys requires a directory");
            System.exit(2);
        }
        Path dir = Path.of(args[1]);
        Files.createDirectories(dir);
        var kp = LicenseService.generateKeyPair();
        Files.writeString(dir.resolve("private.pem"), LicenseService.encodePrivateKey(kp.getPrivate()));
        Files.writeString(dir.resolve("public.pem"), LicenseService.encodePublicKey(kp.getPublic()));
    }

    private static void issue(String[] args) {
        String business = arg(args, "--business");
        String plan = arg(args, "--plan");
        String privateKey = System.getenv("LICENSE_PRIVATE_KEY");
        String pkArg = arg(args, "--private-key");
        if (pkArg != null) privateKey = pkArg;
        if (business == null || plan == null || privateKey == null || privateKey.isBlank()) {
            System.err.println("issue requires --business, --plan and LICENSE_PRIVATE_KEY (or --private-key)");
            usage(2);
        }
        Instant issuedAt = Instant.now();
        String expiresArg = arg(args, "--expires");
        String daysArg = arg(args, "--days");
        boolean perpetual = has(args, "--perpetual");
        Instant expiresAt = null;
        if (perpetual) {
            expiresAt = null;
        } else if (expiresArg != null) {
            expiresAt = Instant.parse(expiresArg);
        } else if (daysArg != null) {
            expiresAt = issuedAt.plus(Long.parseLong(daysArg), ChronoUnit.DAYS);
        } else {
            System.err.println("issue requires one of --days N, --expires <ISO-8601>, or --perpetual");
            usage(2);
        }
        String fingerprint = arg(args, "--fingerprint");
        if (fingerprint == null || fingerprint.isBlank()) {
            System.err.println("issue requires --fingerprint: the till's Machine ID from Kiosk Desktop → Settings → License");
            usage(2);
        }
        LicensePayload payload = new LicensePayload(business, plan, issuedAt, expiresAt, fingerprint);
        String token = LicenseService.encodeToken(payload, LicenseService.decodePrivateKey(privateKey));
        System.out.println(token);
        System.err.println("# business=" + business + " plan=" + plan
            + " expires=" + (expiresAt == null ? "perpetual" : expiresAt));
    }

    private static void verify(String[] args) {
        String publicKey = System.getenv("LICENSE_PUBLIC_KEY");
        String pkArg = arg(args, "--public-key");
        if (pkArg != null) publicKey = pkArg;
        String token = arg(args, "--token");
        if (publicKey == null || publicKey.isBlank() || token == null || token.isBlank()) {
            System.err.println("verify requires --token and LICENSE_PUBLIC_KEY (or --public-key)");
            usage(2);
        }
        // LicenseService's public constructor doubles as a standalone verifier.
        LicenseService svc = new LicenseService(publicKey);
        LicensePayload payload = svc.decodeAndVerify(token);
        if (payload == null) {
            System.err.println("VERIFY=FAILED (bad signature, corrupt token, or wrong public key)");
            System.exit(1);
        }
        System.out.println("VERIFY=OK");
        System.out.println("businessName=" + payload.businessName());
        System.out.println("plan=" + payload.plan());
        System.out.println("issuedAt=" + payload.issuedAt());
        System.out.println("expiresAt=" + payload.expiresAt());
        System.out.println("machineFingerprint=" + payload.machineFingerprint());
    }

    private static String arg(String[] args, String name) {
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].equals(name)) return args[i + 1];
        }
        return null;
    }

    private static boolean has(String[] args, String name) {
        for (String a : args) if (a.equals(name)) return true;
        return false;
    }

    private static void usage(int code) {
        System.err.println("commands: bootstrap | pubkey | keys | issue | verify");
        System.err.println("  keys  — generate a new Ed25519 key pair (stdout)");
        System.err.println("  issue — --business NAME --plan counter|shop|lan (--days N | --expires ISO | --perpetual) --fingerprint MACHINE_ID");
        System.err.println("  verify— --token TOKEN [--public-key BASE64 | LICENSE_PUBLIC_KEY]");
        System.exit(code);
    }
}
JAVA

if ! javac -cp "$CLASSPATH" -d /tmp "$HARNESS" 2>/tmp/license-cli-compile.err; then
  echo "compile failed:" >&2
  cat /tmp/license-cli-compile.err >&2
  echo "" >&2
  echo "Hint: run ./gradlew compileJava first (the backend sources changed since build/classes)." >&2
  exit 1
fi

CMD="${1:-}"
case "$CMD" in
  bootstrap)
    # ── One-command setup: keys → store → bake → print cloud env ─────────
    FORCE=0
    [ "${2:-}" = "--force" ] && FORCE=1
    if [ -f "$PRIVATE_KEY_FILE" ] && [ -f "$PUBLIC_KEY_FILE" ] && [ "$FORCE" = 0 ]; then
      echo "  keys already exist at $KEY_DIR — reusing (bootstrap --force to regenerate)."
    else
      mkdir -p "$KEY_DIR"
      java -cp "/tmp:$CLASSPATH" PalmartLicenseCli writekeys "$KEY_DIR"
      chmod 600 "$PRIVATE_KEY_FILE" "$PUBLIC_KEY_FILE"
      echo "  generated key pair → $KEY_DIR (private.pem / public.pem, mode 600)"
    fi

    PUB="$(cat "$PUBLIC_KEY_FILE")"
    python3 - "$PROPS" "$PUB" << 'PY'
import sys, pathlib
props = pathlib.Path(sys.argv[1])
pub = sys.argv[2]
text = props.read_text()
target = "app.desktop.license.public-key=${APP_DESKTOP_LICENSE_PUBLIC_KEY:" + pub + "}"
if target in text:
    print("  application-desktop.properties already carries this public key — no change.")
    sys.exit(0)
lines = text.splitlines()
out, done = [], False
for ln in lines:
    if ln.startswith("app.desktop.license.public-key="):
        out.append(target)
        done = True
    else:
        out.append(ln)
if not done:
    out.append(target)
props.write_text("\n".join(out) + "\n")
print("  patched application-desktop.properties — public key will be baked into the next release.")
PY

    echo
    echo "  ✅ Desktop JARs will verify licenses once the next release ships."
    echo "  ☁  Set this env var on the CLOUD deployment, then restart:"
    echo "     APP_DESKTOP_LICENSE_PRIVATE_KEY=$(cat "$PRIVATE_KEY_FILE")"
    echo "  🔑 Keep $PRIVATE_KEY_FILE safe — anyone with it can mint licenses."
    echo "     Back it up; never commit it or send it to support."
    ;;
  *)
    # keys / issue / verify — delegate to the Java CLI
    java -cp "/tmp:$CLASSPATH" PalmartLicenseCli "$@"
    ;;
esac
