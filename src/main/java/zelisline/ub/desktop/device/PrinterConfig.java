package zelisline.ub.desktop.device;

/**
 * Persisted as {@code APP_DATA/conf/printer.json} and read by the Tauri device sidecar.
 */
public record PrinterConfig(
    String mode,
    String host,
    int port,
    String path
) {
    public static PrinterConfig defaults() {
        return new PrinterConfig("file", "", 9100, "");
    }
}
