package zelisline.ub.integrations.whatsapp.api;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.integrations.whatsapp.api.dto.WhatsAppChannelDtos.NumberRow;
import zelisline.ub.integrations.whatsapp.api.dto.WhatsAppChannelDtos.NumbersResponse;
import zelisline.ub.integrations.whatsapp.api.dto.WhatsAppChannelDtos.RouteNumberRequest;
import zelisline.ub.integrations.whatsapp.application.WhatsAppChannelRouteAdminService;

/**
 * Super-admin management of Meta WhatsApp numbers: list, route to a shop, pause/resume.
 *
 * <p>Authorized by the {@code /api/v1/super-admin/**} rule in {@code SecurityConfig}
 * ({@code hasRole("SUPER_ADMIN")}) — same as {@code SuperAdminPlatformIntegrationsController},
 * where the Meta keys themselves are edited. See {@code docs/scopes/whatsapp-crm/SCOPE.md} §8.
 */
@Validated
@RestController
@RequestMapping("/api/v1/super-admin/whatsapp/numbers")
@RequiredArgsConstructor
public class SuperAdminWhatsAppChannelController {

    private final WhatsAppChannelRouteAdminService routeAdminService;

    @GetMapping
    public NumbersResponse list() {
        return routeAdminService.getForSuperAdmin();
    }

    @PutMapping("/{phoneNumberId}")
    public NumberRow route(
            @PathVariable String phoneNumberId,
            @Valid @RequestBody RouteNumberRequest body
    ) {
        return routeAdminService.route(phoneNumberId, body);
    }

    @PostMapping("/{phoneNumberId}/pause")
    public NumberRow pause(@PathVariable String phoneNumberId) {
        return routeAdminService.pause(phoneNumberId);
    }

    @PostMapping("/{phoneNumberId}/resume")
    public NumberRow resume(@PathVariable String phoneNumberId) {
        return routeAdminService.resume(phoneNumberId);
    }
}
