package zelisline.ub.domainoffer.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.domainoffer.domain.DomainOfferOrder;
import zelisline.ub.domainoffer.domain.DomainOfferStatus;
import zelisline.ub.domainoffer.repository.DomainOfferOrderRepository;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.application.FrontendAuthLinkBuilder;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.storefront.application.ShopperPhoneEmails;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.domain.DomainSource;
import zelisline.ub.tenancy.domain.DomainStatus;
import zelisline.ub.tenancy.domain.TenantStatus;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Who gets the offer: a live shop without a custom domain, with one owner/admin
 * contact for the email. In-app notifications are business-wide, so a shop with
 * no deliverable email still receives the offer in the bell.
 */
@Service
@RequiredArgsConstructor
public class DomainOfferAudienceService {

    private static final Set<String> CONTACT_ROLE_KEYS = Set.of("owner", "admin");
    private static final int RECENT_OFFER_WINDOW_DAYS = 60;

    private final BusinessRepository businessRepository;
    private final DomainMappingRepository domainMappingRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final DomainOfferOrderRepository orderRepository;
    private final FrontendAuthLinkBuilder frontendAuthLinkBuilder;

    @Value("${app.tenancy.slug-domain-suffix:}")
    private String slugDomainSuffix;

    public record Recipient(
            Business business,
            User contact,
            String contactName,
            String contactEmail,
            String suggestedDomain,
            String shopHost
    ) {
    }

    @Transactional(readOnly = true)
    public List<Recipient> resolve() {
        List<Business> live = businessRepository.findByDeletedAtIsNull().stream()
                .filter(b -> b.getDeletedAt() == null)
                .filter(Business::isActive)
                .filter(b -> b.getTenantStatus() == null || b.getTenantStatus() == TenantStatus.ACTIVE)
                .filter(b -> b.getSlug() != null && !b.getSlug().isBlank())
                .toList();
        if (live.isEmpty()) {
            return List.of();
        }

        Set<String> shopsWithCustomDomain = shopsWithCustomDomain();
        Map<String, User> contacts = ownerContacts();
        Instant recentCutoff = Instant.now().minus(RECENT_OFFER_WINDOW_DAYS, ChronoUnit.DAYS);

        List<Recipient> out = new ArrayList<>();
        for (Business business : live) {
            if (shopsWithCustomDomain.contains(business.getId())) {
                continue;
            }
            if (hasRecentOffer(business.getId(), recentCutoff)) {
                continue;
            }
            User contact = contacts.get(business.getId());
            String email = contact == null ? null : contact.getEmail();
            String name = contact == null ? null : contact.getName();
            out.add(new Recipient(
                    business,
                    contact,
                    name,
                    deliverableEmail(email),
                    suggestedDomain(business.getSlug()),
                    shopHost(business.getSlug())));
        }
        out.sort(Comparator.comparing(
                (Recipient r) -> r.business().getName() == null ? "" : r.business().getName(),
                String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** A display-safe shop host, e.g. {@code powermart.kiosk.ke}. */
    public String shopHost(String slug) {
        String clean = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        String suffix = displaySuffix();
        if (clean.isBlank()) {
            return suffix;
        }
        return clean + "." + suffix;
    }

    public String shopUrl(String slug) {
        return "https://" + shopHost(slug);
    }

    /**
     * Prefer the real tenant origin from the auth link builder: it knows the
     * configured suffix and the local dev port, so emailed and in-app links
     * work outside production too.
     */
    public String shopUrl(Business business) {
        if (business == null || business.getId() == null) {
            return shopUrl((String) null);
        }
        String origin = frontendAuthLinkBuilder.tenantOrigin(business.getId());
        if (origin != null && !origin.isBlank()) {
            return origin;
        }
        return shopUrl(business.getSlug());
    }

    public String suggestedDomain(String slug) {
        String clean = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        return clean.isBlank() ? "" : clean + ".co.ke";
    }

    private String displaySuffix() {
        String suffix = slugDomainSuffix == null ? "" : slugDomainSuffix.trim().toLowerCase(Locale.ROOT);
        if (suffix.isEmpty() || "localhost".equals(suffix)) {
            return "kiosk.ke";
        }
        return suffix;
    }

    private Set<String> shopsWithCustomDomain() {
        Set<String> out = new HashSet<>();
        for (DomainMapping domain : domainMappingRepository.findByDeletedAtIsNull()) {
            if (domain.getSource() == DomainSource.PLATFORM_SUBDOMAIN) {
                continue;
            }
            if (domain.getStatus() == DomainStatus.FAILED) {
                continue;
            }
            out.add(domain.getBusinessId());
        }
        return out;
    }

    private boolean hasRecentOffer(String businessId, Instant since) {
        List<DomainOfferStatus> statuses = new ArrayList<>(List.of(DomainOfferStatus.values()));
        statuses.remove(DomainOfferStatus.CANCELLED);
        statuses.remove(DomainOfferStatus.EXPIRED);
        statuses.remove(DomainOfferStatus.REFUNDED);
        List<DomainOfferOrder> recent = orderRepository.findRecentByBusiness(businessId, statuses, since);
        return recent.stream().anyMatch(o -> !o.getStatus().isClosed());
    }

    private Map<String, User> ownerContacts() {
        Map<String, Role> contactRoles = new HashMap<>();
        for (Role role : roleRepository.findAll()) {
            if (role.getDeletedAt() != null || role.getRoleKey() == null) {
                continue;
            }
            if (CONTACT_ROLE_KEYS.contains(role.getRoleKey().trim().toLowerCase(Locale.ROOT))) {
                contactRoles.put(role.getId(), role);
            }
        }
        if (contactRoles.isEmpty()) {
            return Map.of();
        }
        Map<String, List<User>> byBusiness = new HashMap<>();
        for (User user : userRepository.findLiveByRoleIds(contactRoles.keySet())) {
            byBusiness.computeIfAbsent(user.getBusinessId(), ignored -> new ArrayList<>()).add(user);
        }
        Map<String, User> picked = new HashMap<>();
        byBusiness.forEach((businessId, users) -> {
            List<User> ordered = users.stream()
                    .sorted(Comparator
                            .comparing((User u) -> "owner".equalsIgnoreCase(
                                    roleKey(contactRoles.get(u.getRoleId()))) ? 0 : 1)
                            .thenComparing(User::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            User chosen = ordered.stream()
                    .filter(u -> deliverableEmail(u.getEmail()) != null)
                    .findFirst()
                    .orElse(ordered.isEmpty() ? null : ordered.getFirst());
            if (chosen != null) {
                picked.put(businessId, chosen);
            }
        });
        return picked;
    }

    private static String roleKey(Role role) {
        return role == null || role.getRoleKey() == null ? "" : role.getRoleKey();
    }

    static String deliverableEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            return null;
        }
        if (ShopperPhoneEmails.isSynthetic(email)) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
