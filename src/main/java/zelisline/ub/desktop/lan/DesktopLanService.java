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

    private final Path lanEnabledFile;
    private final int serverPort;

    public DesktopLanService(
            @Value("${APP_DATA:${user.home}/.palmart}") String appDataDir,
            @Value("${server.port:5050}") int serverPort) {
        this.lanEnabledFile = Path.of(appDataDir, "conf", "lan-enabled");
        this.serverPort = serverPort;
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
            } else {
                Files.deleteIfExists(lanEnabledFile);
                log.info("[LAN] disabled — server will bind to 127.0.0.1 on next restart");
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
        return new LanStatus(enabled, url, addresses, serverPort);
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
        return new LanStatus(nowEnabled, url, addresses, serverPort, true);
    }
}
