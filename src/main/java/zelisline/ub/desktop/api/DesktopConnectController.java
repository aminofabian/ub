package zelisline.ub.desktop.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zelisline.ub.desktop.api.dto.DesktopConnectRequest;
import zelisline.ub.desktop.api.dto.DesktopConnectResponse;
import zelisline.ub.desktop.application.DesktopConnectService;

/**
 * "Sign in with my online shop" — first-run alternative to the create-shop
 * wizard (see {@code DesktopConnectService}). Like the setup endpoint it is
 * unauthenticated (no local users exist yet) and idempotent on the service
 * side (409 once the install is initialized).
 */
@RestController
@Profile("desktop")
@RequestMapping("/api/v1/desktop")
@RequiredArgsConstructor
public class DesktopConnectController {

    private final DesktopConnectService desktopConnectService;

    @PostMapping("/connect")
    public DesktopConnectResponse connect(
            @Valid @RequestBody DesktopConnectRequest request) {
        return desktopConnectService.connect(request);
    }
}
