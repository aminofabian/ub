package zelisline.ub.integrations.pickupmtaani.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniDestinationsResponse;
import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniGeoOption;
import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniQuoteRequest;
import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniQuoteResponse;
import zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniQuoteService;
import zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniSettingsService;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient;
import zelisline.ub.storefront.application.PublicStorefrontContextService;

/**
 * Anonymous storefront endpoints for the Pickup Mtaani destination picker and
 * delivery quote (scope §7, §15). The tenant's API key stays server-side; the
 * browser only ever sees geo options and a priced quote id. Slug-scoped to match
 * the rest of the public storefront surface.
 */
@Validated
@RestController
@RequestMapping("/api/v1/public/businesses/{slug}/pickup-mtaani")
@RequiredArgsConstructor
public class PublicPickupMtaaniController {

    private final PublicStorefrontContextService storefrontContextService;
    private final PickupMtaaniSettingsService settingsService;
    private final PickupMtaaniClient pickupMtaaniClient;
    private final PickupMtaaniQuoteService quoteService;

    @GetMapping("/destinations")
    public PickupMtaaniDestinationsResponse destinations(
            @PathVariable String slug,
            @RequestParam(name = "mode", required = false) String mode,
            @RequestParam(name = "zoneId", required = false) Long zoneId,
            @RequestParam(name = "areaId", required = false) Long areaId,
            @RequestParam(name = "locationId", required = false) Long locationId,
            @RequestParam(name = "q", required = false) String q
    ) {
        String businessId = businessIdFor(slug);
        PickupMtaaniSettingsService.PickupMtaaniResolved config = requireReady(businessId);

        if (PickupMtaaniQuoteService.MODE_DOORSTEP.equals(normalizeMode(mode))) {
            return new PickupMtaaniDestinationsResponse(
                    "doorstep",
                    toDtos(pickupMtaaniClient.listDoorstepDestinations(config.apiKey(), areaId, q)));
        }
        if (locationId != null) {
            return new PickupMtaaniDestinationsResponse(
                    "agent",
                    toDtos(pickupMtaaniClient.listAgents(config.apiKey(), locationId, "destination", q)));
        }
        if (areaId != null) {
            return new PickupMtaaniDestinationsResponse(
                    "location",
                    toDtos(pickupMtaaniClient.listLocations(config.apiKey(), areaId, "destination", q)));
        }
        return new PickupMtaaniDestinationsResponse(
                "area",
                toDtos(pickupMtaaniClient.listAreas(config.apiKey(), zoneId)));
    }

    @PostMapping("/quote")
    public PickupMtaaniQuoteResponse quote(
            @PathVariable String slug,
            @Valid @RequestBody PickupMtaaniQuoteRequest body
    ) {
        String businessId = businessIdFor(slug);
        return quoteService.createQuote(businessId, body);
    }

    private String businessIdFor(String slug) {
        return storefrontContextService.requireForSlug(slug).business().getId();
    }

    private PickupMtaaniSettingsService.PickupMtaaniResolved requireReady(String businessId) {
        PickupMtaaniSettingsService.PickupMtaaniResolved config = settingsService.resolve(businessId);
        if (!config.ready()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Pickup Mtaani is not available for this shop.");
        }
        return config;
    }

    private static String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String mode = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return (PickupMtaaniQuoteService.MODE_AGENT.equals(mode)
                || PickupMtaaniQuoteService.MODE_DOORSTEP.equals(mode)) ? mode : null;
    }

    private static List<PickupMtaaniGeoOption> toDtos(List<PickupMtaaniClient.GeoOption> options) {
        return options.stream()
                .map(o -> new PickupMtaaniGeoOption(o.id(), o.name(), o.zoneId(), o.areaId(), o.locationId()))
                .toList();
    }
}
