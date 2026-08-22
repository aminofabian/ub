package zelisline.ub.desktop.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.NetworkInterface;

/**
 * Stable per-machine identity used to bind licenses to a specific till
 * (Settings → License shows the Machine ID; the vendor bakes it into the
 * token with {@code --fingerprint <id>} and the runtime rejects any token
 * whose fingerprint doesn't match this machine).
 *
 * <p>Raw identity sources, in order (all stable across reboots):
 * <ol>
 *   <li>Windows: {@code HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid}</li>
 *   <li>macOS: {@code IOPlatformUUID} (via {@code ioreg})</li>
 *   <li>Linux: {@code /etc/machine-id} (fallback {@code /var/lib/dbus/machine-id})</li>
 *   <li>Fallback: the first hardware MAC address</li>
 * </ol>
 * The raw id is hashed with SHA-256 (lowercase hex digest) so the stored/
 * displayed value is the exact string the vendor signs into a token.
 *
 * <p>{@code APP_DESKTOP_MACHINE_ID} overrides the raw source — mainly for VMs
 * and testing, where none of the hardware sources are meaningful.
 */
@Component
@Profile("desktop")
public class MachineFingerprint implements MachineFingerprintProvider {

    private static final Logger log = LoggerFactory.getLogger(MachineFingerprint.class);

    /** Windows MachineGuid is a GUID (hex + dashes). */
    private static final Pattern WINDOWS_GUID = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );

    /** {@code "IOPlatformUUID" = "…"} in the ioreg dump. */
    private static final Pattern MAC_UUID = Pattern.compile(
        "\"IOPlatformUUID\"\\s*=\\s*\"([^\"]+)\""
    );

    private final String override;
    private volatile String cached;

    public MachineFingerprint(@Value("${APP_DESKTOP_MACHINE_ID:}") String override) {
        this.override = override == null || override.isBlank() ? null : override.trim();
    }

    @Override
    public String get() {
        String value = cached;
        if (value != null) {
            return value;
        }
        synchronized (this) {
            if (cached == null) {
                cached = sha256Hex(resolveRawId());
                log.info("[License] machine fingerprint computed (source: {})",
                    override != null ? "override" : osSourceName());
            }
            return cached;
        }
    }

    // ── raw identity resolution ────────────────────────────────────────────

    private String resolveRawId() {
        if (override != null) {
            return override;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String id = windowsMachineGuid();
            if (id != null) return id;
        } else if (os.contains("mac")) {
            String id = macPlatformUuid();
            if (id != null) return id;
        } else if (os.contains("linux")) {
            String id = linuxMachineId();
            if (id != null) return id;
        }
        String mac = hardwareMac();
        return mac == null ? "unknown-machine" : mac;
    }

    private String osSourceName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows-machine-guid";
        if (os.contains("mac")) return "mac-io-platform-uuid";
        if (os.contains("linux")) return "linux-machine-id";
        return "mac-address";
    }

    private String windowsMachineGuid() {
        String output = run("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
            "/v", "MachineGuid");
        if (output == null) return null;
        Matcher m = WINDOWS_GUID.matcher(output);
        return m.find() ? m.group().replace("-", "").toLowerCase(Locale.ROOT) : null;
    }

    private String macPlatformUuid() {
        String output = run("ioreg", "-rd1", "-c", "IOPlatformExpertDevice");
        if (output == null) return null;
        Matcher m = MAC_UUID.matcher(output);
        return m.find() ? m.group(1).trim().toLowerCase(Locale.ROOT) : null;
    }

    private String linuxMachineId() {
        for (String candidate : List.of(
            "/etc/machine-id",
            "/var/lib/dbus/machine-id")) {
            Path p = Path.of(candidate);
            if (Files.isReadable(p)) {
                try {
                    String id = Files.readString(p, StandardCharsets.UTF_8).trim();
                    if (!id.isBlank()) {
                        return id.toLowerCase(Locale.ROOT);
                    }
                } catch (IOException ignored) {
                    // try the next candidate
                }
            }
        }
        return null;
    }

    /** First non-loopback, non-virtual interface with a non-zero MAC. */
    private String hardwareMac() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return null;
            List<String> candidates = new ArrayList<>();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()) continue;
                byte[] mac = ni.getHardwareAddress();
                if (mac == null || mac.length == 0) continue;
                StringBuilder sb = new StringBuilder();
                boolean allZero = true;
                for (byte b : mac) {
                    if (b != 0) allZero = false;
                    sb.append(String.format("%02x", b));
                }
                if (!allZero) candidates.add(sb.toString());
            }
            return candidates.stream()
                .sorted(Comparator.naturalOrder())
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            log.warn("[License] could not read MAC addresses: {}", e.getMessage());
            return null;
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String run(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            process.waitFor();
            return output;
        } catch (IOException | InterruptedException e) {
            log.debug("[License] command failed: {} {}", String.join(" ", command), e.getMessage());
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
