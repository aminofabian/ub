package zelisline.ub.storefront.application;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.storefront.api.dto.CheckoutProfileSnapshot;
import zelisline.ub.storefront.api.dto.CheckoutStepCompletion;
import zelisline.ub.storefront.api.dto.PatchCheckoutContactRequest;
import zelisline.ub.storefront.api.dto.PatchCheckoutDeliveryRequest;
import zelisline.ub.storefront.api.dto.PublicCheckoutStateResponse;
import zelisline.ub.storefront.api.dto.WebOrderDetailResponse;
import zelisline.ub.storefront.application.ShopperOrderNotesParser.ParsedOrderNotes;
import zelisline.ub.storefront.domain.ShopperCheckoutProfile;
import zelisline.ub.storefront.domain.WebCheckoutSession;
import zelisline.ub.storefront.repository.ShopperCheckoutProfileRepository;
import zelisline.ub.storefront.repository.WebCheckoutSessionRepository;

@Service
@RequiredArgsConstructor
public class ShopperCheckoutStateService {

    public static final String GUEST_KEY_HEADER = "X-Checkout-Guest-Key";

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE_CODE = Pattern.compile("^(\\+\\d{1,4})\\s*(.+)$");

    private final PublicStorefrontContextService storefrontContextService;
    private final PublicWebCartService publicWebCartService;
    private final ShopperCheckoutProfileRepository profileRepository;
    private final WebCheckoutSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final WebOrderAdminService webOrderAdminService;

    @Transactional(readOnly = true)
    public PublicCheckoutStateResponse getState(String slug, String cartId, HttpServletRequest request) {
        var ctx = storefrontContextService.requireForSlug(slug);
        String businessId = ctx.business().getId();
        String cart = cartId.trim();
        publicWebCartService.requireActiveCart(ctx, cart);

        return CurrentTenantUser.optionalHuman(request)
                .map(principal -> buildAuthenticatedState(businessId, principal))
                .orElseGet(() -> buildGuestState(businessId, cart, request));
    }

    @Transactional
    public PublicCheckoutStateResponse saveContact(
            String slug,
            String cartId,
            HttpServletRequest request,
            PatchCheckoutContactRequest body
    ) {
        var ctx = storefrontContextService.requireForSlug(slug);
        String businessId = ctx.business().getId();
        String cart = cartId.trim();
        publicWebCartService.requireActiveCart(ctx, cart);

        var signedIn = CurrentTenantUser.optionalHuman(request);
        if (signedIn.isPresent()) {
            TenantPrincipal principal = signedIn.get();
            ShopperCheckoutProfile profile = loadOrCreateUserProfile(businessId, principal.userId());
            applyContact(profile, body);
            profile.setContactCompletedAt(Instant.now());
            profileRepository.save(profile);
            return buildAuthenticatedState(businessId, principal);
        }

        WebCheckoutSession session = loadOrCreateGuestSession(businessId, cart, request);
        applyContact(session, body);
        session.setContactCompletedAt(Instant.now());
        sessionRepository.save(session);
        return buildGuestStateFromSession(session);
    }

    @Transactional
    public PublicCheckoutStateResponse saveDelivery(
            String slug,
            String cartId,
            HttpServletRequest request,
            PatchCheckoutDeliveryRequest body
    ) {
        var ctx = storefrontContextService.requireForSlug(slug);
        String businessId = ctx.business().getId();
        String cart = cartId.trim();
        publicWebCartService.requireActiveCart(ctx, cart);

        var signedIn = CurrentTenantUser.optionalHuman(request);
        if (signedIn.isPresent()) {
            TenantPrincipal principal = signedIn.get();
            ShopperCheckoutProfile profile = loadOrCreateUserProfile(businessId, principal.userId());
            requireContactComplete(profile.getContactCompletedAt(), isContactComplete(profile));
            applyDelivery(profile, body);
            profile.setDeliveryCompletedAt(Instant.now());
            profileRepository.save(profile);
            return buildAuthenticatedState(businessId, principal);
        }

        WebCheckoutSession session = loadOrCreateGuestSession(businessId, cart, request);
        requireContactComplete(session.getContactCompletedAt(), isContactComplete(session));
        applyDelivery(session, body);
        session.setDeliveryCompletedAt(Instant.now());
        session.setSaveForNextTime(body.saveForNextTime());
        if (body.saveForNextTime()) {
            String guestKey = CheckoutGuestKey.derive(session.getEmail(), session.getAreaCode(), session.getPhone());
            session.setGuestKey(guestKey);
            upsertGuestProfile(businessId, guestKey, session);
        }
        sessionRepository.save(session);
        return buildGuestStateFromSession(session);
    }

    private PublicCheckoutStateResponse buildAuthenticatedState(String businessId, TenantPrincipal principal) {
        ShopperCheckoutProfile profile = profileRepository
                .findByBusinessIdAndUserId(businessId, principal.userId())
                .orElseGet(() -> backfillUserProfileFromHistory(businessId, principal.userId()));

        return toResponse(true, profile, null);
    }

    private PublicCheckoutStateResponse buildGuestState(
            String businessId,
            String cartId,
            HttpServletRequest request
    ) {
        var existing = sessionRepository.findByBusinessIdAndCartId(businessId, cartId);
        if (existing.isPresent()) {
            return buildGuestStateFromSession(existing.get());
        }

        String guestKey = readGuestKey(request);
        if (guestKey != null && !guestKey.isBlank()) {
            var saved = profileRepository.findByBusinessIdAndGuestKey(businessId, guestKey.trim());
            if (saved.isPresent()) {
                WebCheckoutSession session = copyProfileToSession(saved.get(), businessId, cartId);
                sessionRepository.save(session);
                return buildGuestStateFromSession(session);
            }
        }

        return emptyGuestState();
    }

    private WebCheckoutSession loadOrCreateGuestSession(
            String businessId,
            String cartId,
            HttpServletRequest request
    ) {
        return sessionRepository.findByBusinessIdAndCartId(businessId, cartId)
                .orElseGet(() -> {
                    String guestKey = readGuestKey(request);
                    if (guestKey != null && !guestKey.isBlank()) {
                        var saved = profileRepository.findByBusinessIdAndGuestKey(businessId, guestKey.trim());
                        if (saved.isPresent()) {
                            return copyProfileToSession(saved.get(), businessId, cartId);
                        }
                    }
                    WebCheckoutSession session = new WebCheckoutSession();
                    session.setBusinessId(businessId);
                    session.setCartId(cartId);
                    session.setCounty("Nairobi");
                    session.setAreaCode("+254");
                    return session;
                });
    }

    private static WebCheckoutSession copyProfileToSession(
            ShopperCheckoutProfile profile,
            String businessId,
            String cartId
    ) {
        WebCheckoutSession session = new WebCheckoutSession();
        session.setBusinessId(businessId);
        session.setCartId(cartId);
        session.setGuestKey(profile.getGuestKey());
        session.setFirstName(profile.getFirstName());
        session.setLastName(profile.getLastName());
        session.setEmail(profile.getEmail());
        session.setAreaCode(profile.getAreaCode());
        session.setPhone(profile.getPhone());
        session.setWhatsapp(profile.getWhatsapp());
        session.setCounty(profile.getCounty());
        session.setSubcounty(profile.getSubcounty());
        session.setWard(profile.getWard());
        session.setStreetAddress(profile.getStreetAddress());
        session.setDeliveryNotes(profile.getDeliveryNotes());
        session.setContactCompletedAt(profile.getContactCompletedAt());
        session.setDeliveryCompletedAt(profile.getDeliveryCompletedAt());
        session.setSaveForNextTime(profile.isDefault());
        return session;
    }

    private void upsertGuestProfile(String businessId, String guestKey, WebCheckoutSession session) {
        ShopperCheckoutProfile profile = profileRepository
                .findByBusinessIdAndGuestKey(businessId, guestKey)
                .orElseGet(() -> {
                    ShopperCheckoutProfile p = new ShopperCheckoutProfile();
                    p.setBusinessId(businessId);
                    p.setGuestKey(guestKey);
                    return p;
                });
        copySessionToProfile(session, profile);
        profile.setDefault(true);
        profile.setDeliveryCompletedAt(Instant.now());
        if (profile.getContactCompletedAt() == null) {
            profile.setContactCompletedAt(Instant.now());
        }
        profileRepository.save(profile);
    }

    private static void copySessionToProfile(WebCheckoutSession session, ShopperCheckoutProfile profile) {
        profile.setFirstName(session.getFirstName());
        profile.setLastName(session.getLastName());
        profile.setEmail(session.getEmail());
        profile.setAreaCode(session.getAreaCode());
        profile.setPhone(session.getPhone());
        profile.setWhatsapp(session.getWhatsapp());
        profile.setCounty(session.getCounty());
        profile.setSubcounty(session.getSubcounty());
        profile.setWard(session.getWard());
        profile.setStreetAddress(session.getStreetAddress());
        profile.setDeliveryNotes(session.getDeliveryNotes());
        profile.setContactCompletedAt(session.getContactCompletedAt());
        profile.setDeliveryCompletedAt(session.getDeliveryCompletedAt());
        profile.setGuestKey(session.getGuestKey());
    }

    private PublicCheckoutStateResponse buildGuestStateFromSession(WebCheckoutSession session) {
        return toResponse(false, session, session.getGuestKey());
    }

    private PublicCheckoutStateResponse toResponse(
            boolean authenticated,
            Object source,
            String guestKey
    ) {
        CheckoutProfileSnapshot snapshot = source instanceof ShopperCheckoutProfile p
                ? snapshotFromProfile(p)
                : snapshotFromSession((WebCheckoutSession) source);

        boolean contactComplete = source instanceof ShopperCheckoutProfile p
                ? isContactComplete(p) && p.getContactCompletedAt() != null
                : isContactComplete((WebCheckoutSession) source)
                        && ((WebCheckoutSession) source).getContactCompletedAt() != null;

        boolean deliveryComplete = source instanceof ShopperCheckoutProfile p
                ? isDeliveryComplete(p) && p.getDeliveryCompletedAt() != null
                : isDeliveryComplete((WebCheckoutSession) source)
                        && ((WebCheckoutSession) source).getDeliveryCompletedAt() != null;

        int currentStep;
        String detailsSubStep;
        if (!contactComplete) {
            currentStep = 1;
            detailsSubStep = "contact";
        } else if (!deliveryComplete) {
            currentStep = 1;
            detailsSubStep = "delivery";
        } else {
            currentStep = 2;
            detailsSubStep = null;
        }

        return new PublicCheckoutStateResponse(
                authenticated,
                currentStep,
                detailsSubStep,
                new CheckoutStepCompletion(contactComplete, deliveryComplete),
                snapshot,
                guestKey
        );
    }

    private ShopperCheckoutProfile backfillUserProfileFromHistory(String businessId, String userId) {
        ShopperCheckoutProfile profile = new ShopperCheckoutProfile();
        profile.setBusinessId(businessId);
        profile.setUserId(userId);

        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId).orElse(null);
        if (user != null) {
            if (user.getEmail() != null && !user.getEmail().isBlank()) {
                profile.setEmail(user.getEmail().trim().toLowerCase(Locale.ROOT));
            }
            if (user.getName() != null && !user.getName().isBlank()) {
                String[] parts = user.getName().trim().split("\\s+", 2);
                profile.setFirstName(parts[0]);
                if (parts.length > 1) {
                    profile.setLastName(parts[1]);
                }
            }
            if (user.getPhone() != null && !user.getPhone().isBlank()) {
                applyPhone(profile, user.getPhone().trim());
            }
        }

        if (profile.getEmail() != null) {
            try {
                var orders = webOrderAdminService.pageOrdersForShopperEmail(
                        businessId,
                        profile.getEmail(),
                        PageRequest.of(0, 1));
                if (!orders.isEmpty()) {
                    WebOrderDetailResponse detail = webOrderAdminService.getOrderForShopperEmail(
                            businessId,
                            orders.getContent().getFirst().id(),
                            profile.getEmail());
                    applyOrderDetail(profile, detail);
                }
            } catch (ResponseStatusException ignored) {
                // no order history
            }
        }

        if (isContactComplete(profile)) {
            profile.setContactCompletedAt(Instant.now());
        }
        if (isDeliveryComplete(profile)) {
            profile.setDeliveryCompletedAt(Instant.now());
        }
        if (isContactComplete(profile) || isDeliveryComplete(profile)) {
            profileRepository.save(profile);
        }

        return profile;
    }

    private void applyOrderDetail(ShopperCheckoutProfile profile, WebOrderDetailResponse detail) {
        if (detail.customerName() != null && !detail.customerName().isBlank()) {
            String[] parts = detail.customerName().trim().split("\\s+", 2);
            if (profile.getFirstName() == null || profile.getFirstName().isBlank()) {
                profile.setFirstName(parts[0]);
            }
            if (parts.length > 1 && (profile.getLastName() == null || profile.getLastName().isBlank())) {
                profile.setLastName(parts[1]);
            }
        }
        if (detail.customerEmail() != null
                && !detail.customerEmail().isBlank()
                && (profile.getEmail() == null || profile.getEmail().isBlank())) {
            profile.setEmail(detail.customerEmail().trim().toLowerCase(Locale.ROOT));
        }
        if (detail.customerPhone() != null
                && !detail.customerPhone().isBlank()
                && (profile.getPhone() == null || profile.getPhone().isBlank())) {
            applyPhone(profile, detail.customerPhone().trim());
        }
        if (detail.notes() != null && !detail.notes().isBlank()) {
            ParsedOrderNotes parsed = ShopperOrderNotesParser.parse(detail.notes());
            if (profile.getStreetAddress() == null || profile.getStreetAddress().isBlank()) {
                profile.setStreetAddress(parsed.fields().get("streetAddress"));
            }
            if (profile.getCounty() == null || profile.getCounty().isBlank()) {
                profile.setCounty(parsed.fields().getOrDefault("county", "Nairobi"));
            }
            if (profile.getSubcounty() == null || profile.getSubcounty().isBlank()) {
                profile.setSubcounty(parsed.fields().get("subCounty"));
            }
            if (profile.getWard() == null || profile.getWard().isBlank()) {
                profile.setWard(parsed.fields().get("ward"));
            }
            if (profile.getWhatsapp() == null || profile.getWhatsapp().isBlank()) {
                profile.setWhatsapp(parsed.fields().get("whatsApp"));
            }
            if (profile.getDeliveryNotes() == null || profile.getDeliveryNotes().isBlank()) {
                profile.setDeliveryNotes(parsed.fields().get("deliveryNotes"));
            }
            if (parsed.defaultAddress()) {
                profile.setDefault(true);
            }
        }
    }

    private static void applyPhone(ShopperCheckoutProfile profile, String raw) {
        Matcher m = PHONE_CODE.matcher(raw);
        if (m.matches()) {
            profile.setAreaCode(m.group(1));
            profile.setPhone(m.group(2).trim());
        } else {
            profile.setAreaCode("+254");
            profile.setPhone(raw);
        }
    }

    private ShopperCheckoutProfile loadOrCreateUserProfile(String businessId, String userId) {
        return profileRepository.findByBusinessIdAndUserId(businessId, userId)
                .orElseGet(() -> backfillUserProfileFromHistory(businessId, userId));
    }

    private static void applyContact(Object target, PatchCheckoutContactRequest body) {
        setField(target, "firstName", trim(body.firstName()));
        setField(target, "lastName", trim(body.lastName()));
        setField(target, "email", normalizeEmail(body.email()));
        setField(target, "areaCode", normalizeAreaCode(body.areaCode()));
        setField(target, "phone", trim(body.phone()));
        setField(target, "whatsapp", blankToNull(body.whatsApp()));
    }

    private static void applyDelivery(Object target, PatchCheckoutDeliveryRequest body) {
        String county = blankToNull(body.county());
        setField(target, "county", county != null ? county : "Nairobi");
        setField(target, "subcounty", trim(body.subCounty()));
        setField(target, "ward", trim(body.ward()));
        setField(target, "streetAddress", trim(body.streetAddress()));
        setField(target, "deliveryNotes", blankToNull(body.deliveryNotes()));
        if (target instanceof ShopperCheckoutProfile profile) {
            profile.setDefault(body.saveForNextTime());
        }
    }

    private static void setField(Object target, String field, String value) {
        if (target instanceof ShopperCheckoutProfile profile) {
            switch (field) {
                case "firstName" -> profile.setFirstName(value);
                case "lastName" -> profile.setLastName(value);
                case "email" -> profile.setEmail(value);
                case "areaCode" -> profile.setAreaCode(value);
                case "phone" -> profile.setPhone(value);
                case "whatsapp" -> profile.setWhatsapp(value);
                case "county" -> profile.setCounty(value);
                case "subcounty" -> profile.setSubcounty(value);
                case "ward" -> profile.setWard(value);
                case "streetAddress" -> profile.setStreetAddress(value);
                case "deliveryNotes" -> profile.setDeliveryNotes(value);
                default -> throw new IllegalArgumentException(field);
            }
        } else if (target instanceof WebCheckoutSession session) {
            switch (field) {
                case "firstName" -> session.setFirstName(value);
                case "lastName" -> session.setLastName(value);
                case "email" -> session.setEmail(value);
                case "areaCode" -> session.setAreaCode(value);
                case "phone" -> session.setPhone(value);
                case "whatsapp" -> session.setWhatsapp(value);
                case "county" -> session.setCounty(value);
                case "subcounty" -> session.setSubcounty(value);
                case "ward" -> session.setWard(value);
                case "streetAddress" -> session.setStreetAddress(value);
                case "deliveryNotes" -> session.setDeliveryNotes(value);
                default -> throw new IllegalArgumentException(field);
            }
        }
    }

    private static boolean isContactComplete(ShopperCheckoutProfile profile) {
        return hasText(profile.getEmail())
                && hasText(profile.getFirstName())
                && hasText(profile.getLastName())
                && hasText(profile.getPhone());
    }

    private static boolean isContactComplete(WebCheckoutSession session) {
        return hasText(session.getEmail())
                && hasText(session.getFirstName())
                && hasText(session.getLastName())
                && hasText(session.getPhone());
    }

    private static boolean isDeliveryComplete(ShopperCheckoutProfile profile) {
        return hasText(profile.getSubcounty())
                && hasText(profile.getWard())
                && hasText(profile.getStreetAddress());
    }

    private static boolean isDeliveryComplete(WebCheckoutSession session) {
        return hasText(session.getSubcounty())
                && hasText(session.getWard())
                && hasText(session.getStreetAddress());
    }

    private static CheckoutProfileSnapshot snapshotFromProfile(ShopperCheckoutProfile profile) {
        return new CheckoutProfileSnapshot(
                nullToEmpty(profile.getFirstName()),
                nullToEmpty(profile.getLastName()),
                nullToEmpty(profile.getEmail()),
                nullToEmpty(profile.getAreaCode()),
                nullToEmpty(profile.getPhone()),
                nullToEmpty(profile.getWhatsapp()),
                nullToEmpty(profile.getCounty()),
                nullToEmpty(profile.getSubcounty()),
                nullToEmpty(profile.getWard()),
                nullToEmpty(profile.getStreetAddress()),
                nullToEmpty(profile.getDeliveryNotes())
        );
    }

    private static CheckoutProfileSnapshot snapshotFromSession(WebCheckoutSession session) {
        return new CheckoutProfileSnapshot(
                nullToEmpty(session.getFirstName()),
                nullToEmpty(session.getLastName()),
                nullToEmpty(session.getEmail()),
                nullToEmpty(session.getAreaCode()),
                nullToEmpty(session.getPhone()),
                nullToEmpty(session.getWhatsapp()),
                nullToEmpty(session.getCounty()),
                nullToEmpty(session.getSubcounty()),
                nullToEmpty(session.getWard()),
                nullToEmpty(session.getStreetAddress()),
                nullToEmpty(session.getDeliveryNotes())
        );
    }

    private static PublicCheckoutStateResponse emptyGuestState() {
        return new PublicCheckoutStateResponse(
                false,
                1,
                "contact",
                new CheckoutStepCompletion(false, false),
                new CheckoutProfileSnapshot("", "", "", "+254", "", "", "Nairobi", "", "", "", ""),
                null
        );
    }

    private static void requireContactComplete(Instant completedAt, boolean fieldsComplete) {
        if (completedAt == null || !fieldsComplete) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Complete contact details first");
        }
    }

    private static String readGuestKey(HttpServletRequest request) {
        return request.getHeader(GUEST_KEY_HEADER);
    }

    private static String normalizeEmail(String raw) {
        String e = trim(raw).toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(e).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid email");
        }
        return e;
    }

    private static String normalizeAreaCode(String raw) {
        String code = trim(raw);
        if (!code.startsWith("+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Area code must start with +");
        }
        return code;
    }

    private static String trim(String s) {
        if (s == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Required field missing");
        }
        String t = s.trim();
        if (t.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Required field missing");
        }
        return t;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
