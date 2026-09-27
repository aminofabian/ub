package zelisline.ub.integrations.pickupmtaani.api;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniGeoOption;
import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniPatchRequest;
import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniSettingsResponse;
import zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Merchant-facing Pickup Mtaani option and the proxied origin picker (scope §6).
 * There is no credential surface here: the API key is super-admin's to set, and
 * a patch that carries one is rejected. Origin search runs server-side so the
 * browser never sees the key.
 */
@Validated
@RestController
@RequestMapping("/api/v1/integrations/pickup-mtaani")
@RequiredArgsConstructor
public class PickupMtaaniIntegrationsController {

    private final PickupMtaaniSettingsService settingsService;

    @GetMapping
    public PickupMtaaniSettingsResponse get(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return settingsService.settings(businessId(request));
    }

    @PutMapping
    public PickupMtaaniSettingsResponse update(
            HttpServletRequest request,
            @Valid @RequestBody PickupMtaaniPatchRequest body
    ) {
        CurrentTenantUser.requireHuman(request);
        return settingsService.update(businessId(request), body);
    }

    @PatchMapping
    public PickupMtaaniSettingsResponse patch(
            HttpServletRequest request,
            @Valid @RequestBody PickupMtaaniPatchRequest body
    ) {
        CurrentTenantUser.requireHuman(request);
        return settingsService.update(businessId(request), body);
    }

    @GetMapping("/zones")
    public List<PickupMtaaniGeoOption> zones(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return settingsService.listZones(businessId(request));
    }

    @GetMapping("/areas")
    public List<PickupMtaaniGeoOption> areas(
            HttpServletRequest request,
            @RequestParam(name = "zoneId", required = false) Long zoneId
    ) {
        CurrentTenantUser.requireHuman(request);
        return settingsService.listAreas(businessId(request), zoneId);
    }

    @GetMapping("/locations")
    public List<PickupMtaaniGeoOption> locations(
            HttpServletRequest request,
            @RequestParam(name = "areaId", required = false) Long areaId,
            @RequestParam(name = "purpose", required = false) String purpose,
            @RequestParam(name = "q", required = false) String q
    ) {
        CurrentTenantUser.requireHuman(request);
        return settingsService.listLocations(businessId(request), areaId, purpose, q);
    }

    @GetMapping("/agents")
    public List<PickupMtaaniGeoOption> agents(
            HttpServletRequest request,
            @RequestParam(name = "locationId", required = false) Long locationId,
            @RequestParam(name = "purpose", required = false) String purpose,
            @RequestParam(name = "q", required = false) String q
    ) {
        CurrentTenantUser.requireHuman(request);
        return settingsService.listAgents(businessId(request), locationId, purpose, q);
    }

    private static String businessId(HttpServletRequest request) {
        return TenantRequestIds.resolveBusinessId(request);
    }
}
