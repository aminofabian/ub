package zelisline.ub.desktop.device;

/**
 * Sends raw ESC/POS bytes to the local device sidecar (Tauri shell on port 19500).
 */
public interface DeviceBridge {

    void printEscPos(byte[] data);

    void openCashDrawer();

    /**
     * Ask the shell to reboot the till stack (JVM + MariaDB). Only the shell can
     * do this; the call returns once the sidecar has accepted the request.
     */
    void restartBackend();

    /** Open the till's data folder in the OS file manager (the JVM is headless). */
    void openDataFolder();

    /**
     * Probe the sidecar for the Settings → Desktop "device bridge" status pill.
     * Never throws — an unreachable bridge is reported as {@code reachable=false}
     * so the caller can render the state instead of failing the request.
     */
    BridgeHealth health();

    /**
     * @param reachable whether the sidecar answered
     * @param cups      whether the sidecar found a CUPS/spooler tool (null = unknown)
     * @param platform  host platform reported by the sidecar (null = unknown)
     * @param error     short reason when {@code reachable} is false
     */
    record BridgeHealth(boolean reachable, Boolean cups, String platform, String error) {}
}
