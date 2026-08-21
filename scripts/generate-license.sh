#!/usr/bin/env bash
# ── Palmart Desktop — vendor license tool ────────────────────────────────────
# Generates the Ed25519 key pair and issues signed license tokens for Kiosk
# Desktop installs. Runs the backend's own LicenseService/LicensePayload
# classes so the token bytes are identical to what the till verifies.
#
#   ./gradlew compileJava first (or run this — it compiles if needed).
#
# Usage:
#   bash backend/scripts/generate-license.sh keys
#       Generates a fresh Ed25519 key pair and prints:
#         PUBLIC_KEY=<base64>   → paste into application-desktop.properties
#                                 (app.desktop.license.public-key=…)
#         PRIVATE_KEY=<base64>  → keep secret, never ship. Feed it back to
#                                 `issue` via LICENSE_PRIVATE_KEY or --private-key.
#
#   LICENSE_PRIVATE_KEY=<base64> \
#   bash backend/scripts/generate-license.sh issue \
#       --business "My Shop" --plan shop --days 365 [--fingerprint <sha256>]
#       Prints the license token to send to the customer.
#
#       --business  must match the shop name entered in the first-run wizard
#                   EXACTLY (case-sensitive compare at runtime).
#       --plan      counter | shop | lan
#       --days N    validity in days from now (or --expires <ISO-8601> for an
#                   exact expiry instant, or --perpetual for no expiry).
#       --fingerprint optional SHA-256 of MAC+disk; carried in the token but
#                   NOT yet enforced by the runtime.
#
#   LICENSE_PUBLIC_KEY=<base64> \
#   bash backend/scripts/generate-license.sh verify --token <token>
#       Round-trip check: decodes + verifies a token against a public key
#       (the same check the till performs) and prints the payload.
set -euo pipefail

case "${1:-}" in
  -h|--help|help)
    sed -n '3,32p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CP="$ROOT/build/classes/java/main"

usage() {
  sed -n '3,32p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-1}"
}

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
import zelisline.ub.desktop.license.LicensePayload;
import zelisline.ub.desktop.license.LicenseService;

public class PalmartLicenseCli {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(2); }
        switch (args[0]) {
            case "keys" -> generateKeys();
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
        System.err.println("# Paste PUBLIC_KEY into application-desktop.properties:");
        System.err.println("#   app.desktop.license.public-key=<PUBLIC_KEY>");
        System.err.println("# Keep PRIVATE_KEY secret — issue tokens with:");
        System.err.println("#   LICENSE_PRIVATE_KEY=<PRIVATE_KEY> bash backend/scripts/generate-license.sh issue \\");
        System.err.println("#       --business \"Shop Name\" --plan shop --days 365");
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
        System.err.println("commands: keys | issue | verify");
        System.err.println("  keys  — generate a new Ed25519 key pair");
        System.err.println("  issue — --business NAME --plan counter|shop|lan (--days N | --expires ISO | --perpetual) [--fingerprint HASH]");
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

java -cp "/tmp:$CLASSPATH" PalmartLicenseCli "$@"
