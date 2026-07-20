package zelisline.ub.storefront.application;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.storefront.api.dto.PublicLeadCaptureRequest;
import zelisline.ub.storefront.api.dto.PublicLeadCaptureResponse;
import zelisline.ub.storefront.domain.ShopperCheckoutProfile;
import zelisline.ub.storefront.repository.ShopperCheckoutProfileRepository;
import zelisline.ub.tenancy.application.StorefrontSettingsService;

@Service
@RequiredArgsConstructor
public class StorefrontLeadCaptureService {

    private final PublicStorefrontContextService storefrontContextService;
    private final StorefrontSettingsService storefrontSettingsService;
    private final ShopperCheckoutProfileRepository profileRepository;

    @Transactional
    public PublicLeadCaptureResponse capture(
            String slug,
            PublicLeadCaptureRequest body,
            HttpServletRequest request
    ) {
        var ctx = storefrontContextService.requireForSlug(slug);
        String businessId = ctx.business().getId();
        String areaCode = normalizeAreaCode(body.areaCode());
        String phone = trimRequired(body.phone(), "Phone number is required");
        String whatsApp = blankToNull(body.whatsApp());
        if (whatsApp == null) {
            whatsApp = phone;
        }

        boolean hasArea = body.deliveryArea() != null && !body.deliveryArea().isBlank();
        boolean hasStreet = body.streetAddress() != null && !body.streetAddress().isBlank();
        if (hasArea != hasStreet) {
            throw badRequest("Delivery area and exact location are both required");
        }

        String areaName = null;
        String street = null;
        if (hasArea) {
            areaName = requireActiveDeliveryArea(
                    ctx.business().getSettings(),
                    body.deliveryArea()
            );
            street = body.streetAddress().trim();
        }

        var signedIn = CurrentTenantUser.optionalHuman(request);
        if (signedIn.isPresent()) {
            TenantPrincipal principal = signedIn.get();
            ShopperCheckoutProfile profile = profileRepository
                    .findByBusinessIdAndUserId(businessId, principal.userId())
                    .orElseGet(() -> {
                        ShopperCheckoutProfile p = new ShopperCheckoutProfile();
                        p.setBusinessId(businessId);
                        p.setUserId(principal.userId());
                        return p;
                    });
            applyLeadFields(profile, areaCode, phone, whatsApp, areaName, street);
            profile.setDefault(true);
            profileRepository.save(profile);
            return new PublicLeadCaptureResponse(true, null, areaName, street);
        }

        String guestKey = CheckoutGuestKey.derive("", areaCode, phone);
        ShopperCheckoutProfile profile = profileRepository
                .findByBusinessIdAndGuestKey(businessId, guestKey)
                .orElseGet(() -> {
                    ShopperCheckoutProfile p = new ShopperCheckoutProfile();
                    p.setBusinessId(businessId);
                    p.setGuestKey(guestKey);
                    return p;
                });
        applyLeadFields(profile, areaCode, phone, whatsApp, areaName, street);
        profile.setGuestKey(guestKey);
        profile.setDefault(true);
        profileRepository.save(profile);
        return new PublicLeadCaptureResponse(true, guestKey, areaName, street);
    }

    public String requireActiveDeliveryArea(String settingsJson, String rawArea) {
        String requested = rawArea == null ? "" : rawArea.trim();
        if (requested.isEmpty()) {
            throw badRequest("Select a delivery area");
        }
        var active = storefrontSettingsService.activeDeliveryAreaNames(settingsJson);
        if (active.isEmpty()) {
            throw badRequest("This store does not deliver to any areas yet");
        }
        for (String name : active) {
            if (name.equalsIgnoreCase(requested)) {
                return name;
            }
        }
        throw badRequest(
                "We do not deliver to that area. Please choose one of the areas we serve."
        );
    }

    private static void applyLeadFields(
            ShopperCheckoutProfile profile,
            String areaCode,
            String phone,
            String whatsApp,
            String areaName,
            String street
    ) {
        profile.setAreaCode(areaCode);
        profile.setPhone(phone);
        profile.setWhatsapp(whatsApp);
        if (areaName != null && street != null) {
            profile.setCounty("Nairobi");
            // Flat delivery model: area name lives in both zone fields for checkout compatibility.
            profile.setSubcounty(areaName);
            profile.setWard(areaName);
            profile.setStreetAddress(street);
            profile.setDeliveryCompletedAt(Instant.now());
        }
    }

    private static String normalizeAreaCode(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) {
            return "+254";
        }
        return v.startsWith("+") ? v : "+" + v;
    }

    private static String trimRequired(String raw, String message) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) {
            throw badRequest(message);
        }
        return v;
    }

    private static String blankToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        return v.isEmpty() ? null : v;
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
