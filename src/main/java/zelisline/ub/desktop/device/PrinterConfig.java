package zelisline.ub.desktop.device;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Persisted as {@code APP_DATA/conf/printer.json} and read by the Tauri device sidecar.
 *
 * Modes: {@code none}, {@code file}, {@code network} (RAW :9100), {@code cups} (USB/system queue).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PrinterConfig(
    String mode,
    String host,
    int port,
    String path,
    String cupsName
) {
    public static PrinterConfig defaults() {
        return new PrinterConfig("file", "", 9100, "", "");
    }
}
