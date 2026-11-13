package zelisline.ub.tenancy.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.opsalerts.api.dto.OpsAlertTestSendResponse;
import zelisline.ub.opsalerts.application.TenantOpsAlertDispatcher;
import zelisline.ub.tenancy.api.dto.ShopAddressChangeResponse;
import zelisline.ub.tenancy.api.dto.ShopAddressCheckResponse;
import zelisline.ub.tenancy.api.dto.ShopAddressStatusResponse;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.domain.DomainSource;
import zelisline.ub.tenancy.domain.DomainStatus;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Lets a shop change its free platform address ({@code name.kiosk.ke}), for example to drop a
 * hyphen or shorten it.
 *
 * <p>The address a shop is reached at is a row in {@code domains}; the business slug is left
 * alone because sync, exports and the shop finder key on it. A change adds the new address,
 * makes it the primary one, and keeps the old address active, so printed QR codes, bookmarks
 * and anyone still signed in on the old address carry on working.
 *
 * <ul>
 *   <li>A name is letters, digits and single hyphens, {@value #MIN_LENGTH}–{@value #MAX_LENGTH}
 *       characters, not a reserved word, and not another shop's address or slug.</li>
 *   <li>A brand-new name can be taken once every {@link #CHANGE_INTERVAL}; going back to an
 *       address the shop already holds is always allowed.</li>
 *   <li>A shop holds at most {@value #MAX_ADDRESSES} platform addresses, so names are not
 *       hoarded. Old ones are only released when the owner removes them.</li>
 *   <li>The owner is told by email, and by WhatsApp or SMS on the verified alert phone. A
 *       failed notice never undoes the change.</li>
 * </ul>
 */
@Service
public class ShopAddressService {

    private static final Logger log = LoggerFactory.getLogger(ShopAddressService.class);

    static final Duration CHANGE_INTERVAL = Duration.ofDays(30);
    static final int MAX_ADDRESSES = 5;
    static final int MIN_LENGTH = 3;
    static final int MAX_LENGTH = 40;

    private static final Pattern LABEL = Pattern.compile("^[a-z0-9][a-z0-9-]*[a-z0-9]$");
    private static final String FROM_DISPLAY = "Kiosk";

    private final BusinessRepository businessRepository;
    private final DomainMappingRepository domainMappingRepository;
    private final ReservedHostnameGuard reservedHostnameGuard;
    private final UserRepository userRepository;
    private final NotificationService outboundMail;
    private final TenantOpsAlertDispatcher ownerMessages;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;
    private final TransactionTemplate transaction;
    private final String slugDomainSuffix;

    public ShopAddressService(
            BusinessRepository businessRepository,
            DomainMappingRepository domainMappingRepository,
            ReservedHostnameGuard reservedHostnameGuard,
            UserRepository userRepository,
            NotificationService outboundMail,
            TenantOpsAlertDispatcher ownerMessages,
            AuditEventPublisher auditEventPublisher,
            AuditEventBuilder auditEventBuilder,
            PlatformTransactionManager transactionManager,
            @Value("${app.tenancy.slug-domain-suffix:}") String slugDomainSuffix
    ) {
        this.businessRepository = businessRepository;
        this.domainMappingRepository = domainMappingRepository;
        this.reservedHostnameGuard = reservedHostnameGuard;
        this.userRepository = userRepository;
        this.outboundMail = outboundMail;
        this.ownerMessages = ownerMessages;
        this.auditEventPublisher = auditEventPublisher;
        this.auditEventBuilder = auditEventBuilder;
        this.transaction = new TransactionTemplate(transactionManager);
        String suffix = slugDomainSuffix == null ? "" : slugDomainSuffix.trim().toLowerCase(Locale.ROOT);
        this.slugDomainSuffix = suffix.isBlank() ? null : suffix;
    }

    /** Overridable clock for tests. */
    Instant now() {
        return Instant.now();
    }

    // ── Read ────────────────────────────────────────────────────────────

    public ShopAddressStatusResponse status(String businessId) {
        return transaction.execute(tx -> {
            requireBusiness(businessId);
            List<DomainMapping> all = domainMappingRepository.findByBusinessId(businessId);
            String current = all.stream()
                    .filter(d -> d.getDeletedAt() == null && d.isPrimary())
                    .map(DomainMapping::getDomain)
                    .findFirst()
                    .orElse(null);
            List<String> others = all.stream()
                    .filter(d -> d.getDeletedAt() == null && !d.isPrimary() && isPlatformAddress(d))
                    .map(DomainMapping::getDomain)
                    .sorted()
                    .toList();
            Instant next = nextNewNameAllowedAt(all);
            boolean waiting = next != null && now().isBefore(next);
            return new ShopAddressStatusResponse(
                    slugDomainSuffix != null,
                    slugDomainSuffix,
                    current,
                    labelOf(current),
                    others,
                    waiting ? next : null,
                    MIN_LENGTH,
                    MAX_LENGTH);
        });
    }

    /** Says whether a name can be used, in words fit to show beside the field. Never throws. */
    public ShopAddressCheckResponse check(String businessId, String rawName) {
        return transaction.execute(tx -> {
            requireBusiness(businessId);
            String label = normalizeLabel(rawName);
            Verdict verdict = judge(businessId, label, domainMappingRepository.findByBusinessId(businessId));
            return new ShopAddressCheckResponse(
                    label,
                    verdict.host(),
                    verdict.problem() == null,
                    verdict.problem());
        });
    }

    // ── Change ──────────────────────────────────────────────────────────

    public ShopAddressChangeResponse change(String businessId, String rawName, String actorUserId) {
        String label = normalizeLabel(rawName);
        Applied applied;
        try {
            applied = transaction.execute(tx -> apply(businessId, label));
        } catch (DataIntegrityViolationException raced) {
            // Two shops asked for the same free name in the same moment; the unique index
            // on domains.domain let exactly one through.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That address has just been taken. Try another.");
        }
        audit(businessId, actorUserId, applied);
        List<String> notified = notifyOwner(businessId, applied);
        return new ShopAddressChangeResponse(
                applied.newHost(),
                applied.previousHost(),
                "https://" + applied.newHost(),
                notified);
    }

    private Applied apply(String businessId, String label) {
        // Serialises changes for one shop, so the limits below cannot be raced past.
        Business business = businessRepository.findByIdAndDeletedAtIsNullForUpdate(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
        List<DomainMapping> all = domainMappingRepository.findByBusinessId(businessId);
        Verdict verdict = judge(businessId, label, all);
        if (verdict.problem() != null) {
            throw new ResponseStatusException(verdict.status(), verdict.problem());
        }
        String host = verdict.host();
        String previousHost = all.stream()
                .filter(d -> d.getDeletedAt() == null && d.isPrimary())
                .map(DomainMapping::getDomain)
                .findFirst()
                .orElse(null);
        Instant now = now();

        String promotedId;
        if (verdict.existingOwnId() != null) {
            promotedId = verdict.existingOwnId();
        } else {
            DomainMapping added = new DomainMapping();
            added.setBusinessId(businessId);
            added.setDomain(host);
            // Not primary yet: the one-primary-per-business index must never see two.
            added.setPrimary(false);
            added.setActive(true);
            added.setStatus(DomainStatus.ACTIVE);
            added.setSource(DomainSource.PLATFORM_SUBDOMAIN);
            added.setZoneSource(null);
            added.setVerifiedAt(now);
            promotedId = domainMappingRepository.saveAndFlush(added).getId();
        }
        domainMappingRepository.clearPrimaryForBusinessExcept(businessId, promotedId, now);
        // The update above clears the persistence context, so load the row afresh.
        DomainMapping promoted = domainMappingRepository.findById(promotedId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Address could not be saved"));
        if (!promoted.isPrimary()) {
            promoted.setPrimary(true);
            domainMappingRepository.saveAndFlush(promoted);
        }
        return new Applied(business.getName(), host, previousHost, verdict.existingOwnId() == null);
    }

    // ── Rules ───────────────────────────────────────────────────────────

    private record Verdict(String host, String problem, HttpStatus status, String existingOwnId) {
        static Verdict no(String host, HttpStatus status, String problem) {
            return new Verdict(host, problem, status, null);
        }
    }

    private Verdict judge(String businessId, String label, List<DomainMapping> all) {
        if (slugDomainSuffix == null) {
            return Verdict.no(null, HttpStatus.CONFLICT, "Free addresses are not set up on this server.");
        }
        if (label == null || label.isBlank()) {
            return Verdict.no(null, HttpStatus.BAD_REQUEST, "Type the name you want.");
        }
        String host = label + "." + slugDomainSuffix;
        if (label.length() < MIN_LENGTH) {
            return Verdict.no(host, HttpStatus.BAD_REQUEST, "Use at least " + MIN_LENGTH + " characters.");
        }
        if (label.length() > MAX_LENGTH) {
            return Verdict.no(host, HttpStatus.BAD_REQUEST, "Use " + MAX_LENGTH + " characters or fewer.");
        }
        if (!LABEL.matcher(label).matches() || label.contains("--")) {
            return Verdict.no(host, HttpStatus.BAD_REQUEST,
                    "Use letters and numbers only. A single hyphen is allowed between them.");
        }
        if (reservedHostnameGuard.isReservedLabel(label)) {
            return Verdict.no(host, HttpStatus.BAD_REQUEST, "That name is reserved. Try another.");
        }

        Optional<DomainMapping> holder = domainMappingRepository.findByDomainAndDeletedAtIsNull(host);
        if (holder.isPresent()) {
            DomainMapping held = holder.get();
            if (!businessId.equals(held.getBusinessId())) {
                return Verdict.no(host, HttpStatus.CONFLICT, "That address is taken. Try another.");
            }
            if (held.isPrimary()) {
                return Verdict.no(host, HttpStatus.CONFLICT, "This is already your address.");
            }
            if (!held.isActive() || held.getStatus() != DomainStatus.ACTIVE) {
                return Verdict.no(host, HttpStatus.CONFLICT, "That address is not active yet.");
            }
            // One of the shop's own earlier addresses: going back to it is always allowed.
            return new Verdict(host, null, HttpStatus.OK, held.getId());
        }
        // Another shop's slug is still how the shop finder and older links reach it.
        Optional<Business> slugOwner = businessRepository.findBySlugAndDeletedAtIsNull(label);
        if (slugOwner.isPresent() && !businessId.equals(slugOwner.get().getId())) {
            return Verdict.no(host, HttpStatus.CONFLICT, "That address is taken. Try another.");
        }

        long held = all.stream().filter(d -> d.getDeletedAt() == null && isPlatformAddress(d)).count();
        if (held >= MAX_ADDRESSES) {
            return Verdict.no(host, HttpStatus.CONFLICT,
                    "You already hold " + MAX_ADDRESSES + " addresses. Remove one you no longer use first.");
        }
        Instant next = nextNewNameAllowedAt(all);
        if (next != null && now().isBefore(next)) {
            long days = Math.max(1, Duration.between(now(), next).plusHours(23).toDays());
            return Verdict.no(host, HttpStatus.CONFLICT,
                    "You changed your address recently. You can pick a new one in "
                            + days + (days == 1 ? " day." : " days."));
        }
        return new Verdict(host, null, HttpStatus.OK, null);
    }

    /**
     * When a brand-new name may next be taken, or null when there is no wait. The first platform
     * address a shop ever got came with sign-up and does not count as a change; removed
     * addresses still count, so removing one does not reset the clock.
     */
    private Instant nextNewNameAllowedAt(List<DomainMapping> all) {
        List<Instant> created = all.stream()
                .filter(this::isPlatformAddress)
                .map(DomainMapping::getCreatedAt)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.naturalOrder())
                .toList();
        if (created.size() < 2) {
            return null;
        }
        return created.get(created.size() - 1).plus(CHANGE_INTERVAL);
    }

    private boolean isPlatformAddress(DomainMapping d) {
        return d.getSource() == DomainSource.PLATFORM_SUBDOMAIN;
    }

    /** Lower-cases, trims, and accepts a pasted full address ({@code name.kiosk.ke}, with or without https). */
    String normalizeLabel(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("https://")) {
            value = value.substring("https://".length());
        } else if (value.startsWith("http://")) {
            value = value.substring("http://".length());
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        if (slugDomainSuffix != null && value.endsWith("." + slugDomainSuffix)) {
            value = value.substring(0, value.length() - slugDomainSuffix.length() - 1);
        }
        return value;
    }

    private String labelOf(String host) {
        if (host == null || slugDomainSuffix == null || !host.endsWith("." + slugDomainSuffix)) {
            return null;
        }
        return host.substring(0, host.length() - slugDomainSuffix.length() - 1);
    }

    private void requireBusiness(String businessId) {
        if (businessRepository.findByIdAndDeletedAtIsNull(businessId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found");
        }
    }

    // ── After the change ────────────────────────────────────────────────

    private record Applied(String shopName, String newHost, String previousHost, boolean newName) {
    }

    private void audit(String businessId, String actorUserId, Applied applied) {
        try {
            Map<String, Object> oldState = new LinkedHashMap<>();
            oldState.put("address", applied.previousHost());
            Map<String, Object> newState = new LinkedHashMap<>();
            newState.put("address", applied.newHost());
            newState.put("newName", applied.newName());
            auditEventPublisher.publish(auditEventBuilder
                    .builder(AuditEventCategory.SYSTEM, AuditEventTypes.BUSINESS_ADDRESS_CHANGED,
                            AuditEventSeverity.WARN)
                    .businessId(businessId)
                    .actor(actorUserId, AuditEventActorType.USER)
                    .target("business", businessId)
                    .source("business_domains")
                    .oldState(oldState)
                    .newState(newState)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to audit address change business={}: {}", businessId, ex.toString());
        }
    }

    /** @return the channels a notice actually went out on: {@code email}, {@code whatsapp}, {@code sms} */
    private List<String> notifyOwner(String businessId, Applied applied) {
        List<String> sent = new ArrayList<>();
        String shop = applied.shopName() == null || applied.shopName().isBlank() ? "Your shop" : applied.shopName().trim();
        String newUrl = "https://" + applied.newHost();
        String oldLine = applied.previousHost() == null
                ? ""
                : " The old address, " + applied.previousHost() + ", still works.";
        String text = shop + ": your shop address is now " + newUrl + "." + oldLine
                + " If you did not make this change, sign in and check Business > Domains.";

        try {
            User owner = userRepository.findOwnersByBusinessIdIn(List.of(businessId)).stream()
                    .findFirst()
                    .orElse(null);
            String email = owner == null || owner.getEmail() == null ? "" : owner.getEmail().trim();
            if (!email.isBlank() && outboundMail.canDeliverEmail()) {
                String html = "<p>" + escape(shop) + ": your shop address is now <a href=\"" + newUrl + "\">"
                        + escape(applied.newHost()) + "</a>.</p>"
                        + (applied.previousHost() == null ? ""
                                : "<p>The old address, " + escape(applied.previousHost()) + ", still works.</p>")
                        + "<p>If you did not make this change, sign in and check Business &gt; Domains.</p>";
                outboundMail.sendNotificationEmail(
                        email, "Your shop address has changed", text, html, FROM_DISPLAY);
                sent.add("email");
            }
        } catch (Exception ex) {
            log.warn("Address-change email failed business={}: {}", businessId, ex.toString());
        }

        try {
            OpsAlertTestSendResponse phone = ownerMessages.sendOwnerMessage(businessId, "auto", text);
            if (phone != null && "sent".equals(phone.outcome()) && phone.channel() != null) {
                sent.add(phone.channel().toLowerCase(Locale.ROOT));
            }
        } catch (Exception ex) {
            // No verified alert phone, or WhatsApp/SMS not set up for this shop: not an error here.
            log.info("Address-change phone notice skipped business={}: {}", businessId, ex.getMessage());
        }
        return sent;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
