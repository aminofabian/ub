package zelisline.ub.desktop.lan;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Current "Share on LAN" state for the Settings UI
 * (see {@code DESKTOP_INSTALLATION.md} §11).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LanStatus(
        /** Whether the LAN marker file exists (will take effect on next restart). */
        boolean enabled,
        /** Full URL for LAN clients, e.g. {@code http://192.168.1.5:5050}, or null. */
        String lanUrl,
        /** All detected non‑loopback IPv4 addresses. */
        List<String> detectedAddresses,
        /** The server port. */
        int port,
        /** True after a toggle — the frontend should tell the user a restart is needed. */
        boolean restartRequired,
        /**
         * Non-null when LAN is on but Windows Firewall has no inbound rule for
         * the port (other devices will be blocked). Contains the admin one-liner
         * to open it. Null when the rule exists or can't be verified.
         */
        String firewallNote
) {
    public LanStatus(boolean enabled, String lanUrl, List<String> addresses, int port) {
        this(enabled, lanUrl, addresses, port, false, null);
    }
}
