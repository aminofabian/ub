package zelisline.ub.desktop.device;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Fallback when the desktop device sidecar is disabled (cloud / dev without shell).
 */
@Component
@ConditionalOnProperty(name = "app.desktop.device.enabled", havingValue = "false")
@Slf4j
public class NoOpDeviceBridge implements DeviceBridge {

    @Override
    public void printEscPos(byte[] data) {
        log.debug("device bridge disabled — dropped {} ESC/POS bytes", data.length);
    }

    @Override
    public void openCashDrawer() {
        log.debug("device bridge disabled — drawer kick ignored");
    }

    @Override
    public void restartBackend() {
        log.debug("device bridge disabled — restart request ignored");
    }

    @Override
    public void openDataFolder() {
        log.debug("device bridge disabled — open data folder ignored");
    }

    @Override
    public BridgeHealth health() {
        return new BridgeHealth(false, null, null, "Device bridge disabled");
    }
}
