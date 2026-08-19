package zelisline.ub.platform.email.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.FrontendAuthLinkBuilder;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.platform.email.api.dto.PlatformEmailCampaignDtos.SaEmailRecipientResponse;
import zelisline.ub.platform.email.domain.PlatformEmailCampaign;
import zelisline.ub.platform.email.domain.PlatformEmailCampaignRecipient;
import zelisline.ub.storefront.application.ShopperPhoneEmails;
import zelisline.ub.tenancy.application.BusinessOnboardingSettingsService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class PlatformEmailAudienceService {

    public static final String ADMIN_ROLE_KEY = "admin";
    public static final String SKIP_MISSING_EMAIL = "missing_email";
    public static final String SKIP_SYNTHETIC_EMAIL = "synthetic_email";

    private static final Set<String> SEGMENTS = Set.of(
            PlatformEmailCampaign.SEGMENT_STUCK_SIGNUP,
            PlatformEmailCampaign.SEGMENT_UNVERIFIED_OWNERS,
            PlatformEmailCampaign.SEGMENT_SELECTED_TENANTS,
            PlatformEmailCampaign.SEGMENT_SELECTED_USERS);

    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BusinessOnboardingSettingsService onboardingSettingsService;
    private final FrontendAuthLinkBuilder frontendAuthLinkBuilder;

    public String normalizeSegment(String raw) {
        String key = raw == null || raw.isBlank()
                ? PlatformEmailCampaign.SEGMENT_STUCK_SIGNUP
                : raw.trim().toLowerCase(Locale.ROOT);
        if (!SEGMENTS.contains(key)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown segment: " + raw);
        }
        return key;
    }

    public List<SaEmailRecipientResponse> resolve(
            String segmentRaw,
            List<String> businessIds,
            List<String> userIds,
            String q
    ) {
        String segment = normalizeSegment(segmentRaw);
        List<String> bizFilter = compact(businessIds);
        List<String> userFilter = compact(userIds);

        List<SaEmailRecipientResponse> rows = switch (segment) {
            case PlatformEmailCampaign.SEGMENT_SELECTED_USERS -> resolveExplicitUsers(userFilter);
            case PlatformEmailCampaign.SEGMENT_SELECTED_TENANTS -> resolvePreferredContacts(loadBusinesses(bizFilter), false, false);
            case PlatformEmailCampaign.SEGMENT_UNVERIFIED_OWNERS -> resolvePreferredContacts(
                    loadBusinesses(bizFilter), true, false);
            default -> resolvePreferredContacts(loadBusinesses(bizFilter), false, true);
        };

        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return rows;
        }
        return rows.stream().filter(r -> matchesQuery(r, needle)).toList();
    }

    public String shopOrigin(String businessId) {
        return frontendAuthLinkBuilder.tenantOrigin(businessId);
    }

    public String continueUrlForPreview(SaEmailRecipientResponse recipient) {
        String origin = shopOrigin(recipient.businessId());
        if (PlatformEmailCampaignRecipient.KIND_VERIFY.equals(recipient.continueKind())) {
            return origin + "/verify-email?token=preview";
        }
        return origin + "/business";
    }

    public String continueUrlForSend(User user, String continueKind, String mintedVerifyLink) {
        if (PlatformEmailCampaignRecipient.KIND_VERIFY.equals(continueKind)) {
            return mintedVerifyLink;
        }
        return shopOrigin(user.getBusinessId()) + "/business";
    }

    static boolean isStuck(String userStatus, Instant lastLoginAt, String onboardingStatus) {
        boolean invited = UserStatus.INVITED.wire().equalsIgnoreCase(userStatus);
        boolean neverLoggedIn = lastLoginAt == null;
        String onb = onboardingStatus == null ? "idle" : onboardingStatus.trim().toLowerCase(Locale.ROOT);
        boolean onboardIncomplete = "idle".equals(onb) || "pending".equals(onb) || "active".equals(onb);
        return invited || neverLoggedIn || onboardIncomplete;
    }

    static String skipReasonForEmail(String email) {
        if (email == null || email.isBlank()) {
            return SKIP_MISSING_EMAIL;
        }
        if (ShopperPhoneEmails.isSynthetic(email) || !email.contains("@")) {
            return email.contains("@") ? SKIP_SYNTHETIC_EMAIL : SKIP_MISSING_EMAIL;
        }
        return null;
    }

    static String continueKind(String userStatus) {
        return UserStatus.INVITED.wire().equalsIgnoreCase(userStatus)
                ? PlatformEmailCampaignRecipient.KIND_VERIFY
                : PlatformEmailCampaignRecipient.KIND_HUB;
    }

    private List<SaEmailRecipientResponse> resolveExplicitUsers(List<String> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        List<User> users = userRepository.findLiveByIds(userIds);
        Map<String, User> byId = new HashMap<>();
        for (User user : users) {
            byId.put(user.getId(), user);
        }
        Map<String, Role> roles = roleById();
        Map<String, Business> businesses = businessById(users.stream().map(User::getBusinessId).distinct().toList());
        List<SaEmailRecipientResponse> out = new ArrayList<>();
        for (String id : userIds) {
            User user = byId.get(id);
            if (user == null) {
                continue;
            }
            Business business = businesses.get(user.getBusinessId());
            if (business == null) {
                continue;
            }
            Role role = roles.get(user.getRoleId());
            out.add(toResponse(user, business, role));
        }
        return out;
    }

    private List<SaEmailRecipientResponse> resolvePreferredContacts(
            List<Business> businesses,
            boolean unverifiedOnly,
            boolean stuckOnly
    ) {
        Map<String, Role> ownerAdminRoles = ownerAdminRoles();
        if (ownerAdminRoles.isEmpty() || businesses.isEmpty()) {
            return List.of();
        }
        List<User> staff = userRepository.findLiveByRoleIds(ownerAdminRoles.keySet());
        Map<String, List<User>> byBusiness = new HashMap<>();
        for (User user : staff) {
            byBusiness.computeIfAbsent(user.getBusinessId(), ignored -> new ArrayList<>()).add(user);
        }

        List<SaEmailRecipientResponse> out = new ArrayList<>();
        for (Business business : businesses) {
            User pick = pickPreferred(byBusiness.getOrDefault(business.getId(), List.of()), ownerAdminRoles);
            if (pick == null) {
                continue;
            }
            Role role = ownerAdminRoles.get(pick.getRoleId());
            SaEmailRecipientResponse row = toResponse(pick, business, role);
            if (unverifiedOnly && !UserStatus.INVITED.wire().equalsIgnoreCase(row.userStatus())) {
                continue;
            }
            if (stuckOnly && !isStuck(row.userStatus(), row.lastLoginAt(), row.onboardingStatus())) {
                continue;
            }
            out.add(row);
        }
        out.sort(Comparator.comparing(SaEmailRecipientResponse::businessName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SaEmailRecipientResponse::email, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private User pickPreferred(List<User> staff, Map<String, Role> ownerAdminRoles) {
        if (staff.isEmpty()) {
            return null;
        }
        List<User> owners = staff.stream()
                .filter(u -> isRole(ownerAdminRoles.get(u.getRoleId()), IdentityService.OWNER_ROLE_KEY))
                .sorted(Comparator.comparing(User::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (!owners.isEmpty()) {
            return owners.getFirst();
        }
        return staff.stream()
                .filter(u -> isRole(ownerAdminRoles.get(u.getRoleId()), ADMIN_ROLE_KEY))
                .sorted(Comparator.comparing(User::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .findFirst()
                .orElse(null);
    }

    private SaEmailRecipientResponse toResponse(User user, Business business, Role role) {
        String onboarding = onboardingSettingsService.readFromSettingsJson(business.getSettings()).status();
        return new SaEmailRecipientResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                role != null ? role.getRoleKey() : "unknown",
                user.getStatus(),
                user.getLastLoginAt(),
                business.getId(),
                business.getName(),
                business.getSlug(),
                onboarding,
                continueKind(user.getStatus()),
                skipReasonForEmail(user.getEmail())
        );
    }

    private List<Business> loadBusinesses(List<String> ids) {
        if (ids.isEmpty()) {
            return businessRepository.findByDeletedAtIsNull();
        }
        List<Business> found = new ArrayList<>();
        for (String id : ids) {
            businessRepository.findByIdAndDeletedAtIsNull(id).ifPresent(found::add);
        }
        return found;
    }

    private Map<String, Business> businessById(List<String> ids) {
        Map<String, Business> map = new HashMap<>();
        for (String id : ids) {
            businessRepository.findByIdAndDeletedAtIsNull(id).ifPresent(b -> map.put(b.getId(), b));
        }
        return map;
    }

    private Map<String, Role> ownerAdminRoles() {
        Map<String, Role> map = new HashMap<>();
        for (Role role : roleRepository.findAll()) {
            if (role.getDeletedAt() != null) {
                continue;
            }
            if (isRole(role, IdentityService.OWNER_ROLE_KEY) || isRole(role, ADMIN_ROLE_KEY)) {
                map.put(role.getId(), role);
            }
        }
        return map;
    }

    private Map<String, Role> roleById() {
        Map<String, Role> map = new HashMap<>();
        for (Role role : roleRepository.findAll()) {
            if (role.getDeletedAt() == null) {
                map.put(role.getId(), role);
            }
        }
        return map;
    }

    private static boolean isRole(Role role, String key) {
        return role != null && key.equalsIgnoreCase(role.getRoleKey());
    }

    private static boolean matchesQuery(SaEmailRecipientResponse row, String needle) {
        return contains(row.email(), needle)
                || contains(row.name(), needle)
                || contains(row.businessName(), needle)
                || contains(row.slug(), needle)
                || contains(row.userId(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static List<String> compact(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .filter(Objects::nonNull)
                .flatMap(s -> java.util.Arrays.stream(s.split(",")))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }
}
