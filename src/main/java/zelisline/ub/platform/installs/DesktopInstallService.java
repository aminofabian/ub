package zelisline.ub.platform.installs;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.billing.domain.SubscriptionBillingStatus;
import zelisline.ub.desktop.license.DesktopLicenseIssue;
import zelisline.ub.desktop.license.DesktopLicenseIssuanceService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Upserts the registry of Kiosk Desktop installs and, when a connected shop has
 * already paid, activates it automatically.
 *
 * <p>Auto-activation is deliberately narrow: it only fires when the install
 * reports the cloud business id it connected to (an unguessable UUID), that
 * business is on a paid tier with {@code ACTIVE} billing, and the machine does
 * not already hold a valid token. A till that was only set up locally stays in
 * the list for a manual one-click issue.
 */
@Service
@RequiredArgsConstructor
public class DesktopInstallService {

    private static final Logger log = LoggerFactory.getLogger(DesktopInstallService.class);

    private static final int AUTO_LICENSE_DAYS = 365;
    private static final String FREE_TIER = "free";
    private static final String DESKTOP_TIER = "desktop";
    private static final String OWNER_ROLE_KEY = "owner";

    private final DesktopInstallRepository installRepository;
    private final DesktopLicenseIssuanceService issuanceService;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    /** Fields a till reports on check-in (see {@code DesktopInstallReporter}). */
    public record Registration(
            String installId,
            String machineId,
            String businessName,
            String contactEmail,
            String appVersion,
            String platform,
            String cloudBusinessId,
            String licenseState,
            String plan) {}

    /**
     * Creates or refreshes the install row. Never fails on auto-activation: a
     * paid shop whose email send breaks still shows up in the console for a
     * manual retry.
     */
    @Transactional
    public DesktopInstall register(Registration registration, String ip) {
        Instant now = Instant.now();
        DesktopInstall row = installRepository
            .findById(registration.installId().trim())
            .orElseGet(() -> {
                DesktopInstall fresh = new DesktopInstall();
                fresh.setInstallId(registration.installId().trim());
                fresh.setFirstSeenAt(now);
                return fresh;
            });

        row.setMachineFingerprint(registration.machineId().trim());
        row.setBusinessName(registration.businessName().trim());
        row.setLastSeenAt(now);
        if (notBlank(registration.contactEmail())) {
            row.setContactEmail(registration.contactEmail().trim());
        }
        if (notBlank(registration.appVersion())) {
            row.setAppVersion(registration.appVersion().trim());
        }
        if (notBlank(registration.platform())) {
            row.setPlatform(registration.platform().trim());
        }
        if (notBlank(registration.licenseState())) {
            row.setLicenseState(registration.licenseState().trim());
        }
        if (notBlank(registration.plan())) {
            row.setPlan(registration.plan().trim().toLowerCase(java.util.Locale.ROOT));
        }
        if (notBlank(registration.cloudBusinessId())) {
            row.setCloudBusinessId(registration.cloudBusinessId().trim());
        }
        if (notBlank(ip)) {
            row.setLastIp(ip.trim());
        }
        DesktopInstall saved = installRepository.save(row);

        tryAutoActivate(saved);
        return saved;
    }

    /**
     * Issues and emails a machine-bound token for an install. Defaults the plan
     * to the shop's cloud tier and the recipient to the install's known owner
     * email when the caller leaves them blank.
     */
    @Transactional
    public DesktopLicenseIssue issueAndEmail(
            DesktopInstall install,
            String email,
            Integer days,
            Boolean perpetual) {
        String recipient = notBlank(email) ? email.trim() : defaultRecipient(install);
        String plan = resolvePlan(install);
        DesktopLicenseIssue issue = issuanceService.issue(
            install.getBusinessName(),
            plan,
            days == null ? AUTO_LICENSE_DAYS : days,
            null,
            perpetual,
            install.getMachineFingerprint(),
            recipient
        );
        install.setLicenseState("active");
        install.setPlan(issue.getPlan());
        install.setLastLicenseIssueId(issue.getId());
        installRepository.save(install);
        return issue;
    }

    private void tryAutoActivate(DesktopInstall install) {
        if (!notBlank(install.getCloudBusinessId())) {
            return;
        }
        String businessId = install.getCloudBusinessId().trim();
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId).orElse(null);
        if (business == null || !isPaid(business)) {
            return;
        }
        if (issuanceService.hasActiveLicenseFor(install.getMachineFingerprint())) {
            return;
        }
        String recipient = defaultRecipient(install);
        if (recipient == null) {
            log.info("[desktop-installs] paid shop {} has no owner email — leaving {} for manual activation",
                businessId, install.getInstallId());
            return;
        }
        try {
            DesktopLicenseIssue issue = issuanceService.issue(
                business.getName(),
                business.getSubscriptionTier() == null ? "starter" : business.getSubscriptionTier(),
                AUTO_LICENSE_DAYS,
                null,
                false,
                install.getMachineFingerprint(),
                recipient
            );
            install.setAutoIssuedAt(Instant.now());
            install.setLastLicenseIssueId(issue.getId());
            install.setLicenseState("active");
            install.setPlan(issue.getPlan());
            installRepository.save(install);
            log.info("[desktop-installs] auto-activated paid shop {} install {} (plan {})",
                businessId, install.getInstallId(), issue.getPlan());
        } catch (RuntimeException e) {
            log.warn("[desktop-installs] auto-activation failed for install {}: {}",
                install.getInstallId(), e.getMessage());
        }
    }

    private boolean isPaid(Business business) {
        if (business.getSubscriptionBillingStatus() != SubscriptionBillingStatus.ACTIVE) {
            return false;
        }
        // Require a live paid period, so a free/default tier that merely defaults
        // to ACTIVE is never activated without an actual subscription.
        if (business.getCurrentPeriodEnd() == null
                || !business.getCurrentPeriodEnd().isAfter(Instant.now())) {
            return false;
        }
        String tier = business.getSubscriptionTier() == null
            ? ""
            : business.getSubscriptionTier().trim().toLowerCase(java.util.Locale.ROOT);
        return !tier.isEmpty() && !FREE_TIER.equals(tier) && !DESKTOP_TIER.equals(tier);
    }

    private String resolvePlan(DesktopInstall install) {
        if (notBlank(install.getCloudBusinessId())) {
            Business business = businessRepository
                .findByIdAndDeletedAtIsNull(install.getCloudBusinessId().trim())
                .orElse(null);
            if (business != null && notBlank(business.getSubscriptionTier())) {
                return business.getSubscriptionTier();
            }
        }
        return notBlank(install.getPlan()) ? install.getPlan() : null;
    }

    /** Owner email from the cloud business, falling back to the email the till reported. */
    private String defaultRecipient(DesktopInstall install) {
        if (notBlank(install.getCloudBusinessId())) {
            String ownerEmail = userRepository
                .findActiveByRoleKeyOrderByCreatedAtAsc(install.getCloudBusinessId().trim(), OWNER_ROLE_KEY)
                .stream()
                .map(User::getEmail)
                .filter(DesktopInstallService::notBlank)
                .findFirst()
                .orElse(null);
            if (ownerEmail != null) {
                return ownerEmail;
            }
        }
        return notBlank(install.getContactEmail()) ? install.getContactEmail().trim() : null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
