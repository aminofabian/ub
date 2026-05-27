package zelisline.ub.desktop.api;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.desktop.api.dto.DesktopSetupRequest;
import zelisline.ub.desktop.api.dto.DesktopSetupResponse;
import zelisline.ub.desktop.api.dto.DesktopSetupStatusResponse;
import zelisline.ub.desktop.application.DesktopSetupService;

/**
 * First-run setup endpoints — desktop SKU only (see {@code DESKTOP_INSTALLATION.md} §9).
 *
 * <p>Both endpoints are intentionally <em>unauthenticated</em>: the install has
 * no users yet. The {@code DesktopWebConfig} security chain whitelists the
 * {@code /api/v1/desktop/setup/**} prefix. Idempotency comes from the service —
 * the second {@code POST} returns 409 once the {@code Business} row exists.
 */
@RestController
@Profile("desktop")
@RequestMapping("/api/v1/desktop/setup")
@RequiredArgsConstructor
public class DesktopSetupController {

    private final DesktopSetupService desktopSetupService;

    /**
     * Cheap probe for the frontend router. Always 200; the {@code setupRequired}
     * flag is what matters.
     */
    @GetMapping("/status")
    public DesktopSetupStatusResponse status() {
        return new DesktopSetupStatusResponse(
                desktopSetupService.isSetupRequired(),
                desktopSetupService.getDesktopBusinessId());
    }

    @PostMapping
    public DesktopSetupResponse setup(@Valid @RequestBody DesktopSetupRequest request) {
        return desktopSetupService.completeSetup(request);
    }
}
