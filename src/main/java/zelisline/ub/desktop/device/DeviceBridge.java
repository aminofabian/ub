package zelisline.ub.desktop.device;

/**
 * Sends raw ESC/POS bytes to the local device sidecar (Tauri shell on port 19500).
 */
public interface DeviceBridge {

    void printEscPos(byte[] data);

    void openCashDrawer();
}
