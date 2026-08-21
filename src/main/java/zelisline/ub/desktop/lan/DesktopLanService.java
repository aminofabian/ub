package zelisline.ub.desktop.lan;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Manages the "Share on LAN" toggle for the desktop SKU
 * (see {@code DESKTOP_INSTALLATION.md} §11).
 *
 * <p>When LAN mode is enabled, the server binds to {@code 0.0.0.0} instead of
 * {@code 127.0.0.1}, allowing other devices on the same Wi‑Fi network to
 * access the POS at {@code http://<lan-ip>:5050}.
 *
 * <p>The toggle writes a marker file ({@code APP_DATA/conf/lan-enabled}) that
 * the shell reads on next boot. Spring cannot rebind its server socket at
 * runtime, so a restart is required — the controller signals this to the
 * frontend via {@link LanStatus#restartRequired()}.
 */
@Service
@Profile("desktop")
public class DesktopLanService {

    private static final Logger log = LoggerFactory.getLogger(DesktopLanService.class);

    private static final String NETSH = "C:\\Windows\\System32\\netsh.exe";

    private final Path lanEnabledFile;
    private final int serverPort;
    /** Windows Firewall rule name used to open the POS port for LAN clients. */
    private final String firewallRuleName;

    public DesktopLanService(
            @Value("${APP_DATA:${user.home}/.palmart}") String appDataDir,
            @Value("${server.port:5050}") int serverPort) {
        this.lanEnabledFile = Path.of(appDataDir, "conf", "lan-enabled");
        this.serverPort = serverPort;
        this.firewallRuleName = "Palmart Kiosk " + serverPort;
    }

    /** Is LAN sharing currently active? (Reads the marker file, not the live bind.) */
    public boolean isLanEnabled() {
        return Files.exists(lanEnabledFile);
    }

    /**
     * Toggle LAN sharing on or off. Writes (or deletes) the marker file.
     * The actual bind change takes effect on the next restart.
     */
    public void setLanEnabled(boolean enabled) {
        try {
            if (enabled) {
                Files.createDirectories(lanEnabledFile.getParent());
                Files.writeString(lanEnabledFile, "true", StandardCharsets.UTF_8);
                log.info("[LAN] enabled — server will bind to 0.0.0.0 on next restart");
                // Best effort: Windows Firewall would otherwise block other
                // devices from reaching this PC even though the bind is 0.0.0.0.
                // Fails silently when the app lacks admin rights — the frontend
                // then shows the manual one-liner from {@link #firewallNote()}.
                applyFirewallRule(true);
            } else {
                Files.deleteIfExists(lanEnabledFile);
                log.info("[LAN] disabled — server will bind to 127.0.0.1 on next restart");
                applyFirewallRule(false);
            }
        } catch (IOException e) {
            log.error("[LAN] failed to write lan-enabled file: {}", e.getMessage());
            throw new RuntimeException("Failed to toggle LAN mode", e);
        }
    }

    /**
     * Detects the best LAN IP address for displaying to the user.
     * Skips loopback, virtual, and Docker interfaces; prefers
     * en0/eth0 (the built‑in Ethernet/Wi‑Fi adapter).
     */
    public List<String> detectLanAddresses() {
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp() || iface.isVirtual()) {
                    continue;
                }
                // Skip Docker / bridge interfaces
                String name = iface.getDisplayName().toLowerCase();
                if (name.contains("docker") || name.contains("bridge") || name.contains("veth")) {
                    continue;
                }
                Enumeration<InetAddress> inetAddresses = iface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress addr = inetAddresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (!addresses.contains(ip)) {
                            addresses.add(ip);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[LAN] failed to enumerate network interfaces: {}", e.getMessage());
        }
        return addresses;
    }

    /** Returns the primary LAN address, or null if none found. */
    public String primaryLanAddress() {
        List<String> addrs = detectLanAddresses();
        // Prefer 192.168.x.x (most common home/office LAN)
        for (String addr : addrs) {
            if (addr.startsWith("192.168.") || addr.startsWith("10.") || addr.startsWith("172.16.")) {
                return addr;
            }
        }
        return addrs.isEmpty() ? null : addrs.get(0);
    }

    /** Returns the full connection URL for LAN clients. */
    public String lanConnectionUrl() {
        String ip = primaryLanAddress();
        if (ip == null) {
            return null;
        }
        return "http://" + ip + ":" + serverPort;
    }

    /** Returns the current status for the frontend. */
    public LanStatus getStatus() {
        boolean enabled = isLanEnabled();
        String url = lanConnectionUrl();
        List<String> addresses = detectLanAddresses();
        return new LanStatus(enabled, url, addresses, serverPort, false, enabled ? firewallNote() : null);
    }

    /**
     * Toggle and return the new status + a restart flag.
     */
    public LanStatus toggle() {
        boolean wasEnabled = isLanEnabled();
        setLanEnabled(!wasEnabled);
        boolean nowEnabled = !wasEnabled;
        String url = nowEnabled ? lanConnectionUrl() : null;
        List<String> addresses = detectLanAddresses();
        return new LanStatus(nowEnabled, url, addresses, serverPort, true, nowEnabled ? firewallNote() : null);
    }

    /**
     * Adds/removes the Windows Firewall inbound rule for the POS port.
     * Best effort: adding rules requires elevation, which the till usually
     * does not have — failures are logged and surfaced via {@link #firewallNote()}.
     */
    private void applyFirewallRule(boolean add) {
        if (!isWindows()) {
            return;
        }
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(NETSH);
            cmd.add("advfirewall");
            cmd.add("firewall");
            cmd.add(add ? "add" : "delete");
            cmd.add("rule");
            cmd.add("name=" + firewallRuleName);
            if (add) {
                cmd.add("dir=in");
                cmd.add("action=allow");
                cmd.add("protocol=TCP");
                cmd.add("localport=" + serverPort);
            }
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = p.inputReader().lines().collect(Collectors.joining("\n"));
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("[LAN] firewall rule {} timed out", add ? "add" : "remove");
                return;
            }
            if (p.exitValue() == 0) {
                log.info("[LAN] firewall rule {}: {}", add ? "added" : "removed", firewallRuleName);
            } else {
                log.warn("[LAN] firewall rule {} failed (exit {}): {}",
                        add ? "add" : "remove", p.exitValue(), out.trim());
            }
        } catch (Exception e) {
            log.warn("[LAN] could not {} firewall rule: {}", add ? "add" : "remove", e.getMessage());
        }
    }

    /**
     * Guidance for the Settings UI when LAN is on but Windows Firewall has no
     * inbound rule for the POS port (other devices will be blocked). Returns
     * {@code null} when the rule exists or cannot be verified (don't nag).
     */
    private String firewallNote() {
        if (!isWindows()) {
            return null;
        }
        try {
            Process p = new ProcessBuilder(NETSH, "advfirewall", "firewall", "show", "rule",
                    "name=" + firewallRuleName).redirectErrorStream(true).start();
            String out = p.inputReader().lines().collect(Collectors.joining("\n"));
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            // English output says "No rules match the specified criteria." when
            // absent; localized output may differ, in which case we stay quiet
            // rather than showing a false warning.
            if (out.toLowerCase().contains("no rules match")) {
                return "Windows Firewall is blocking other devices from reaching this PC."
                        + " Run this once in PowerShell as Administrator, then try the URL from the other device again:\n"
                        + "netsh advfirewall firewall add rule name=\"" + firewallRuleName
                        + "\" dir=in action=allow protocol=TCP localport=" + serverPort;
            }
            return null;
        } catch (Exception e) {
            log.warn("[LAN] could not check firewall rule: {}", e.getMessage());
            return null;
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
