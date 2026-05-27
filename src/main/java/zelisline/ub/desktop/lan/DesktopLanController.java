package zelisline.ub.desktop.lan;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * LAN sharing endpoints for the desktop SKU
 * (see {@code DESKTOP_INSTALLATION.md} §11).
 *
 * <p>These require authentication — the Settings page is behind the login wall.
 * The {@code /status} endpoint is safe to call from any authenticated page; the
 * {@code /toggle} endpoint should be gated to the owner role by the frontend.
 */
@RestController
@Profile("desktop")
@RequestMapping("/api/v1/desktop/lan")
@RequiredArgsConstructor
public class DesktopLanController {

    private final DesktopLanService lanService;

    /** Current LAN status — connection URL, detected IPs, enabled state. */
    @GetMapping("/status")
    public LanStatus status() {
        return lanService.getStatus();
    }

    /**
     * Toggle LAN sharing on/off. Returns the new state with
     * {@code restartRequired = true} because the server must restart for the
     * bind address change to take effect.
     */
    @PostMapping("/toggle")
    public LanStatus toggle() {
        return lanService.toggle();
    }
}
