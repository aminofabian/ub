package zelisline.ub.onboarding.sequence.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.notifications.NotificationCategories;
import zelisline.ub.onboarding.sequence.MerchantOnboardingStep;
import zelisline.ub.onboarding.sequence.domain.MerchantOnboardingEnrollment;
import zelisline.ub.onboarding.sequence.domain.MerchantOnboardingSend;
import zelisline.ub.onboarding.sequence.repository.MerchantOnboardingEnrollmentRepository;
import zelisline.ub.onboarding.sequence.repository.MerchantOnboardingSendRepository;
import zelisline.ub.opsalerts.application.BusinessOpsAlertSettingsService;
import zelisline.ub.opsalerts.application.TenantOpsAlertDispatcher;
import zelisline.ub.opsalerts.domain.OpsAlertType;
import zelisline.ub.platform.email.application.PlatformCampaignEmailRenderer;
import zelisline.ub.platform.email.application.PlatformEmailAudienceService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.TenantStatus;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class MerchantOnboardingSequenceService {

    private static final Logger log = LoggerFactory.getLogger(MerchantOnboardingSequenceService.class);
    private static final String FROM_DISPLAY = "Kiosk";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_WHATSAPP_PER_ENROLLMENT = 3;
    /** First web order WA only when total is at least this (major currency units). */
    private static final BigDecimal NOTABLE_WEB_ORDER_TOTAL = new BigDecimal("1000");
    /** Email send failures retry within 6h, max 2 re-attempts (brief §10). */
    private static final int MAX_EMAIL_RETRIES = 2;
    private static final Duration EMAIL_RETRY_DELAY = Duration.ofHours(6);

    private final MerchantOnboardingEnrollmentRepository enrollmentRepository;
    private final MerchantOnboardingSendRepository sendRepository;
    private final MerchantOnboardingGateService gateService;
    private final MerchantOnboardingMessageRenderer messageRenderer;
    private final MerchantOnboardingMuteToken muteToken;
    private final PlatformCampaignEmailRenderer campaignEmailRenderer;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final NotificationService outboundMail;
    private final zelisline.ub.notifications.application.NotificationService inAppNotifications;
    private final PlatformEmailAudienceService audienceService;
    private final TenantOpsAlertDispatcher opsAlertDispatcher;
    private final BusinessOpsAlertSettingsService opsAlertSettings;
    private final ObjectMapper objectMapper;

    @Value("${app.public.api-base-url:http://localhost:5050}")
    private String apiBaseUrl;

    /**
     * Enroll a tenant operator after welcome. Marks M0 as already sent (welcome path).
     */
    @Transactional
    public void enrollAfterWelcome(String businessId, String ownerUserId) {
        if (businessId == null || ownerUserId == null) {
            return;
        }
        if (enrollmentRepository.existsById(businessId)) {
            return;
        }
        MerchantOnboardingEnrollment enrollment =
                MerchantOnboardingEnrollment.enroll(businessId, ownerUserId);
        enrollmentRepository.save(enrollment);
        recordOutcome(businessId, MerchantOnboardingStep.M0_WELCOME, MerchantOnboardingSend.CHANNEL_EMAIL,
                MerchantOnboardingSend.STATUS_SENT, null);
        recordOutcome(businessId, MerchantOnboardingStep.M0_WELCOME, MerchantOnboardingSend.CHANNEL_IN_APP,
                MerchantOnboardingSend.STATUS_SENT, null);
        log.info("onboarding sequence enrolled businessId={} ownerUserId={}", businessId, ownerUserId);
    }

    @Transactional
    public void mute(String businessId) {
        enrollmentRepository.findById(businessId).ifPresent(row -> {
            row.setMutedAt(Instant.now());
            enrollmentRepository.save(row);
            log.info("onboarding sequence muted businessId={}", businessId);
        });
    }

    @Transactional(readOnly = true)
    public boolean isMuted(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return false;
        }
        return enrollmentRepository.findById(businessId)
                .map(row -> row.getMutedAt() != null)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isEnrolled(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return false;
        }
        return enrollmentRepository.existsById(businessId);
    }

    /** Public mute via signed email token. @return true if muted. */
    @Transactional
    public boolean muteWithToken(String token) {
        String businessId = muteToken.verifyBusinessId(token);
        if (businessId == null) {
            return false;
        }
        mute(businessId);
        return true;
    }

    /** First completed sale: stamp milestone, WhatsApp now, schedule EOD email, send in-app now. */
    @Transactional
    public void onFirstSale(String businessId) {
        MerchantOnboardingEnrollment enrollment = enrollmentRepository.findById(businessId).orElse(null);
        if (enrollment == null || enrollment.getMutedAt() != null) {
            return;
        }
        MerchantOnboardingGateService.Snapshot snap = gateService.snapshot(businessId);
        gateService.refreshMilestones(enrollment, snap);
        boolean first = enrollment.getFirstSaleAt() == null;
        if (first) {
            enrollment.setFirstSaleAt(Instant.now());
        }
        if (enrollment.getM4EmailDueAt() == null) {
            enrollment.setM4EmailDueAt(gateService.endOfLocalDay(Instant.now(), snap.zone()));
        }
        enrollmentRepository.save(enrollment);

        skipIfPending(businessId, MerchantOnboardingStep.M4_FALLBACK, "first_sale");

        if (!alreadyHandled(businessId, MerchantOnboardingStep.M4_FIRST_SALE, MerchantOnboardingSend.CHANNEL_IN_APP)) {
            sendInApp(businessId, MerchantOnboardingStep.M4_FIRST_SALE, snap);
        }
        if (first) {
            maybeSendWhatsApp(businessId, MerchantOnboardingStep.M4_FIRST_SALE, snap);
        }
    }

    /** After catalog create — suggest grouping when lookalikes appear. */
    @Transactional
    public void onCatalogChanged(String businessId) {
        MerchantOnboardingEnrollment enrollment = enrollmentRepository.findById(businessId).orElse(null);
        if (enrollment == null || enrollment.getMutedAt() != null || enrollment.getCompletedAt() != null) {
            return;
        }
        MerchantOnboardingGateService.Snapshot snap = gateService.snapshot(businessId);
        gateService.refreshMilestones(enrollment, snap);
        enrollmentRepository.save(enrollment);
        if (!snap.hasLookalikeProducts()) {
            return;
        }
        if (alreadyHandled(businessId, MerchantOnboardingStep.N1_LOOKALIKE, MerchantOnboardingSend.CHANNEL_IN_APP)) {
            return;
        }
        // In-app only — WhatsApp reserved for M4 / fallback / W / notable N4 (cap of 3).
        sendInApp(businessId, MerchantOnboardingStep.N1_LOOKALIKE, snap);
    }

    /** First open shift — remind them to close it tonight (in-app only). */
    @Transactional
    public void onFirstShiftOpened(String businessId) {
        MerchantOnboardingEnrollment enrollment = enrollmentRepository.findById(businessId).orElse(null);
        if (enrollment == null || enrollment.getMutedAt() != null || enrollment.getCompletedAt() != null) {
            return;
        }
        if (alreadyHandled(businessId, MerchantOnboardingStep.N2_CLOSE_SHIFT, MerchantOnboardingSend.CHANNEL_IN_APP)) {
            return;
        }
        MerchantOnboardingGateService.Snapshot snap = gateService.snapshot(businessId);
        sendInApp(businessId, MerchantOnboardingStep.N2_CLOSE_SHIFT, snap);
    }

    /** First web order — in-app always; WhatsApp only when total is notable. */
    @Transactional
    public void onFirstWebOrder(String businessId, BigDecimal grandTotal) {
        MerchantOnboardingEnrollment enrollment = enrollmentRepository.findById(businessId).orElse(null);
        if (enrollment == null || enrollment.getMutedAt() != null || enrollment.getCompletedAt() != null) {
            return;
        }
        MerchantOnboardingGateService.Snapshot snap = gateService.snapshot(businessId);
        if (!alreadyHandled(businessId, MerchantOnboardingStep.N4_WEB_ORDER, MerchantOnboardingSend.CHANNEL_IN_APP)) {
            sendInApp(businessId, MerchantOnboardingStep.N4_WEB_ORDER, snap);
        }
        // Only notable orders attempt WhatsApp. Do NOT record a "not_notable" skip:
        // any merchant_onboarding_send row makes the step permanently handled, which
        // would silently kill the WhatsApp for a later, larger web order.
        if (grandTotal != null && grandTotal.compareTo(NOTABLE_WEB_ORDER_TOTAL) >= 0) {
            maybeSendWhatsApp(businessId, MerchantOnboardingStep.N4_WEB_ORDER, snap);
        }
    }

    /** Hourly tick: evaluate due steps for active enrollments. */
    @Transactional
    public int processDueBatch() {
        Instant now = Instant.now();
        int processed = 0;
        int page = 0;
        while (true) {
            List<MerchantOnboardingEnrollment> batch = enrollmentRepository
                    .findByMutedAtIsNullAndCompletedAtIsNull(PageRequest.of(page, PAGE_SIZE));
            if (batch.isEmpty()) {
                break;
            }
            for (MerchantOnboardingEnrollment enrollment : batch) {
                try {
                    if (processOne(enrollment, now)) {
                        processed++;
                    }
                } catch (RuntimeException ex) {
                    log.warn("onboarding sequence failed businessId={}", enrollment.getBusinessId(), ex);
                }
            }
            if (batch.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        return processed;
    }

    private boolean processOne(MerchantOnboardingEnrollment enrollment, Instant now) {
        String businessId = enrollment.getBusinessId();
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId).orElse(null);
        if (business == null
                || business.getTenantStatus() == TenantStatus.SUSPENDED
                || business.getTenantStatus() == TenantStatus.INACTIVE) {
            return false;
        }

        MerchantOnboardingGateService.Snapshot snap = gateService.snapshot(businessId);
        gateService.refreshMilestones(enrollment, snap);
        enrollmentRepository.save(enrollment);

        ZoneId zone = snap.zone();
        Instant enrolledAt = enrollment.getEnrolledAt();
        boolean changed = false;

        // M1 — after questionnaire soft-complete (completed/dismissed) OR 24h after enroll
        if (!emailDone(businessId, MerchantOnboardingStep.M1_FILL_SHELF)) {
            Instant m1Due = gateService.m1DueAt(enrolledAt, zone);
            boolean unlocked = snap.questionnaireDone()
                    || enrolledAt.plus(Duration.ofHours(24)).isBefore(now);
            if (unlocked && !m1Due.isAfter(now)) {
                if (snap.sellableSkuCount() >= 1) {
                    skipStep(businessId, MerchantOnboardingStep.M1_FILL_SHELF, "already_stocked");
                } else {
                    deliver(businessId, MerchantOnboardingStep.M1_FILL_SHELF, snap);
                }
                changed = true;
            }
        }

        // M2 — day 2 after enroll, or sooner on lookalikes; skip if no products / no lookalikes and only catalog
        if (!emailDone(businessId, MerchantOnboardingStep.M2_SIZES)
                && alreadySentOrSkipped(businessId, MerchantOnboardingStep.M1_FILL_SHELF)) {
            Instant day2 = enrolledAt.plus(Duration.ofDays(2));
            boolean due = !day2.isAfter(now) || (snap.hasLookalikeProducts() && snap.sellableSkuCount() >= 2);
            if (due) {
                if (snap.sellableSkuCount() == 0) {
                    skipStep(businessId, MerchantOnboardingStep.M2_SIZES, "no_products");
                } else if (!snap.hasLookalikeProducts()
                        && snap.catalogImportCount() >= snap.sellableSkuCount()
                        && snap.sellableSkuCount() > 0) {
                    // Pure catalog import with no lookalikes — still teach briefly on day 2
                    // unless they have zero manual orphans. Soft skip only when no lookalikes
                    // AND all items came from catalog (no size confusion detected).
                    skipStep(businessId, MerchantOnboardingStep.M2_SIZES, "catalog_clean");
                } else {
                    deliver(businessId, MerchantOnboardingStep.M2_SIZES, snap);
                }
                changed = true;
            }
        }

        // M3 — ≥1 SKU and ~24h after first sellable (or enroll+2d); skip if supply posted
        if (!emailDone(businessId, MerchantOnboardingStep.M3_MONEY_LOOP)) {
            Instant sellableAt = enrollment.getFirstSellableAt();
            Instant m3Due = sellableAt != null
                    ? sellableAt.plus(Duration.ofHours(24))
                    : enrolledAt.plus(Duration.ofDays(2));
            if (snap.sellableSkuCount() >= 1 && !m3Due.isAfter(now)) {
                if (snap.hasPostedSupply()) {
                    skipStep(businessId, MerchantOnboardingStep.M3_MONEY_LOOP, "supply_posted");
                } else {
                    deliver(businessId, MerchantOnboardingStep.M3_MONEY_LOOP, snap);
                }
                changed = true;
            }
        }

        // M4 EOD email after first sale
        if (enrollment.getFirstSaleAt() != null
                && enrollment.getM4EmailDueAt() != null
                && !enrollment.getM4EmailDueAt().isAfter(now)
                && !emailDone(businessId, MerchantOnboardingStep.M4_FIRST_SALE)) {
            deliverEmailOnly(businessId, MerchantOnboardingStep.M4_FIRST_SALE, snap);
            changed = true;
        }

        // M4 fallback — no sale by day 3
        if (!snap.hasCompletedSale()
                && !emailDone(businessId, MerchantOnboardingStep.M4_FALLBACK)
                && !emailDone(businessId, MerchantOnboardingStep.M4_FIRST_SALE)
                && enrolledAt.plus(Duration.ofDays(3)).isBefore(now)) {
            deliver(businessId, MerchantOnboardingStep.M4_FALLBACK, snap);
            maybeSendWhatsApp(businessId, MerchantOnboardingStep.M4_FALLBACK, snap);
            changed = true;
        }

        // M5 — after first sale + 24h, or day 5
        if (!emailDone(businessId, MerchantOnboardingStep.M5_GO_LIVE)) {
            Instant day5 = enrolledAt.plus(Duration.ofDays(5));
            Instant afterSale = enrollment.getFirstSaleAt() == null
                    ? null
                    : enrollment.getFirstSaleAt().plus(Duration.ofHours(24));
            boolean due = (afterSale != null && !afterSale.isAfter(now))
                    || (!day5.isAfter(now) && snap.sellableSkuCount() >= 1);
            if (due && snap.sellableSkuCount() >= 1) {
                deliver(businessId, MerchantOnboardingStep.M5_GO_LIVE, snap);
                changed = true;
            }
        }

        // M6 — day 6–7; skip when the team is already running (staff invited + a closed shift)
        if (!emailDone(businessId, MerchantOnboardingStep.M6_TEAM)
                && !enrolledAt.plus(Duration.ofDays(6)).isAfter(now)) {
            if (snap.hasInvitedStaff() && snap.hasClosedShift()) {
                skipStep(businessId, MerchantOnboardingStep.M6_TEAM, "team_running");
            } else {
                deliver(businessId, MerchantOnboardingStep.M6_TEAM, snap);
            }
            changed = true;
        }

        // W — day 7–8; keep the enrollment open until the email is settled so a
        // failed W email can still be retried (max 2) before we close it out.
        if (!emailDone(businessId, MerchantOnboardingStep.W_WEEK_CHECKIN)
                && !enrolledAt.plus(Duration.ofDays(7)).isAfter(now)) {
            deliver(businessId, MerchantOnboardingStep.W_WEEK_CHECKIN, snap);
            maybeSendWhatsApp(businessId, MerchantOnboardingStep.W_WEEK_CHECKIN, snap);
            changed = true;
        }
        if (enrollment.getCompletedAt() == null
                && emailSettled(businessId, MerchantOnboardingStep.W_WEEK_CHECKIN)
                && alreadyHandled(businessId, MerchantOnboardingStep.W_WEEK_CHECKIN, MerchantOnboardingSend.CHANNEL_IN_APP)) {
            enrollment.setCompletedAt(Instant.now());
            enrollmentRepository.save(enrollment);
            changed = true;
        }

        return changed;
    }

    private void deliver(String businessId, MerchantOnboardingStep step, MerchantOnboardingGateService.Snapshot snap) {
        deliverEmailOnly(businessId, step, snap);
        sendInApp(businessId, step, snap);
    }

    private void deliverEmailOnly(
            String businessId,
            MerchantOnboardingStep step,
            MerchantOnboardingGateService.Snapshot snap
    ) {
        MerchantOnboardingSend existing = sendRepository
                .findByBusinessIdAndStepKeyAndChannel(businessId, step.key(), MerchantOnboardingSend.CHANNEL_EMAIL)
                .orElse(null);
        if (existing != null) {
            if (!MerchantOnboardingSend.STATUS_FAILED.equals(existing.getStatus())) {
                return; // sent or skipped — done
            }
            if (existing.getRetryCount() >= MAX_EMAIL_RETRIES) {
                return; // retries exhausted
            }
            if (existing.getNextRetryAt() != null && existing.getNextRetryAt().isAfter(Instant.now())) {
                return; // retry not due yet
            }
        }
        Optional<User> owner = resolveOwner(businessId);
        if (owner.isEmpty() || owner.get().getEmail() == null || owner.get().getEmail().isBlank()) {
            if (existing != null) {
                existing.setStatus(MerchantOnboardingSend.STATUS_SKIPPED);
                existing.setSkipReason("missing_email");
                existing.setNextRetryAt(null);
                sendRepository.save(existing);
            } else {
                recordOutcome(businessId, step, MerchantOnboardingSend.CHANNEL_EMAIL,
                        MerchantOnboardingSend.STATUS_SKIPPED, "missing_email");
            }
            return;
        }
        User user = owner.get();
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId).orElse(null);
        String shopUrl = audienceService.shopOrigin(businessId);
        String muteUrl = muteLink(businessId);
        var rendered = messageRenderer.render(
                step,
                user.getName(),
                business != null ? business.getName() : null,
                shopUrl,
                snap,
                muteUrl);
        String ctaUrl = shopUrl.replaceAll("/$", "") + rendered.ctaPath();
        String html = campaignEmailRenderer.renderHtml(
                rendered.subject(),
                rendered.innerBodyHtml() != null
                        ? rendered.innerBodyHtml()
                        : MerchantOnboardingMessageRenderer.toHtmlParagraphs(rendered.plainBody()),
                rendered.ctaLabel(),
                ctaUrl,
                shopUrl,
                rendered.previewText());
        try {
            outboundMail.sendPlatformCampaignEmail(
                    user.getEmail(),
                    rendered.subject(),
                    rendered.plainBody(),
                    html,
                    FROM_DISPLAY);
            if (existing != null) {
                existing.setStatus(MerchantOnboardingSend.STATUS_SENT);
                existing.setSentAt(Instant.now());
                existing.setRetryCount(0);
                existing.setNextRetryAt(null);
                sendRepository.save(existing);
            } else {
                recordOutcome(businessId, step, MerchantOnboardingSend.CHANNEL_EMAIL,
                        MerchantOnboardingSend.STATUS_SENT, null);
            }
            log.info("onboarding email sent businessId={} step={} to={}", businessId, step.key(), user.getEmail());
        } catch (RuntimeException ex) {
            log.warn("onboarding email failed businessId={} step={}", businessId, step.key(), ex);
            int retries = (existing == null ? 0 : existing.getRetryCount()) + 1;
            Instant nextRetry = retries >= MAX_EMAIL_RETRIES ? null : Instant.now().plus(EMAIL_RETRY_DELAY);
            if (existing != null) {
                existing.setRetryCount(retries);
                existing.setNextRetryAt(nextRetry);
                sendRepository.save(existing);
            } else {
                recordEmailFailure(businessId, step, retries, nextRetry);
            }
        }
    }

    private void recordEmailFailure(
            String businessId,
            MerchantOnboardingStep step,
            int retries,
            Instant nextRetry
    ) {
        MerchantOnboardingSend row = new MerchantOnboardingSend();
        row.setBusinessId(businessId);
        row.setStepKey(step.key());
        row.setChannel(MerchantOnboardingSend.CHANNEL_EMAIL);
        row.setStatus(MerchantOnboardingSend.STATUS_FAILED);
        row.setSkipReason("send_failed");
        row.setDedupeKey(step.key() + ":" + MerchantOnboardingSend.CHANNEL_EMAIL);
        row.setRetryCount(retries);
        row.setNextRetryAt(nextRetry);
        try {
            sendRepository.save(row);
        } catch (DataIntegrityViolationException ex) {
            // concurrent tick
        }
    }

    /**
     * Email step is "done for now": sent, skipped, retries exhausted, or a retry is
     * pending. A FAILED row with a due {@code next_retry_at} is NOT done — it retries.
     */
    private boolean emailDone(String businessId, MerchantOnboardingStep step) {
        Optional<MerchantOnboardingSend> row = sendRepository
                .findByBusinessIdAndStepKeyAndChannel(businessId, step.key(), MerchantOnboardingSend.CHANNEL_EMAIL);
        if (row.isEmpty()) {
            return false;
        }
        MerchantOnboardingSend send = row.get();
        if (!MerchantOnboardingSend.STATUS_FAILED.equals(send.getStatus())) {
            return true;
        }
        if (send.getRetryCount() >= MAX_EMAIL_RETRIES) {
            return true;
        }
        Instant next = send.getNextRetryAt();
        return next != null && next.isAfter(Instant.now());
    }

    /** Email step will never send again: sent, skipped, or retries exhausted. */
    private boolean emailSettled(String businessId, MerchantOnboardingStep step) {
        Optional<MerchantOnboardingSend> row = sendRepository
                .findByBusinessIdAndStepKeyAndChannel(businessId, step.key(), MerchantOnboardingSend.CHANNEL_EMAIL);
        if (row.isEmpty()) {
            return false;
        }
        MerchantOnboardingSend send = row.get();
        if (!MerchantOnboardingSend.STATUS_FAILED.equals(send.getStatus())) {
            return true;
        }
        return send.getRetryCount() >= MAX_EMAIL_RETRIES;
    }

    private void sendInApp(
            String businessId,
            MerchantOnboardingStep step,
            MerchantOnboardingGateService.Snapshot snap
    ) {
        if (alreadyHandled(businessId, step, MerchantOnboardingSend.CHANNEL_IN_APP)) {
            return;
        }
        Optional<User> owner = resolveOwner(businessId);
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId).orElse(null);
        var rendered = messageRenderer.render(
                step,
                owner.map(User::getName).orElse(null),
                business != null ? business.getName() : null,
                audienceService.shopOrigin(businessId),
                snap);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", rendered.inAppTitle());
        payload.put("body", rendered.inAppBody());
        payload.put("actionUrl", rendered.ctaPath());
        payload.put("step", step.key());
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        inAppNotifications.tryInsertDedupe(
                businessId,
                step.notificationType(),
                "onboarding:" + step.key() + ":" + businessId,
                NotificationCategories.ENGAGEMENT,
                "MEDIUM",
                json);
        recordOutcome(businessId, step, MerchantOnboardingSend.CHANNEL_IN_APP,
                MerchantOnboardingSend.STATUS_SENT, null);
    }

    private void skipStep(String businessId, MerchantOnboardingStep step, String reason) {
        skipIfPending(businessId, step, reason);
    }

    private void skipIfPending(String businessId, MerchantOnboardingStep step, String reason) {
        for (String channel : List.of(
                MerchantOnboardingSend.CHANNEL_EMAIL, MerchantOnboardingSend.CHANNEL_IN_APP)) {
            if (!alreadyHandled(businessId, step, channel)) {
                recordOutcome(businessId, step, channel, MerchantOnboardingSend.STATUS_SKIPPED, reason);
            }
        }
    }

    private boolean alreadySentOrSkipped(String businessId, MerchantOnboardingStep step) {
        return alreadyHandled(businessId, step, MerchantOnboardingSend.CHANNEL_EMAIL)
                || alreadyHandled(businessId, step, MerchantOnboardingSend.CHANNEL_IN_APP);
    }

    private boolean alreadyHandled(String businessId, MerchantOnboardingStep step, String channel) {
        return sendRepository.existsByBusinessIdAndStepKeyAndChannel(businessId, step.key(), channel);
    }

    private void recordOutcome(
            String businessId,
            MerchantOnboardingStep step,
            String channel,
            String status,
            String skipReason
    ) {
        if (sendRepository.existsByBusinessIdAndStepKeyAndChannel(businessId, step.key(), channel)) {
            return;
        }
        MerchantOnboardingSend row = new MerchantOnboardingSend();
        row.setBusinessId(businessId);
        row.setStepKey(step.key());
        row.setChannel(channel);
        row.setStatus(status);
        row.setSkipReason(skipReason);
        row.setDedupeKey(step.key() + ":" + channel);
        if (MerchantOnboardingSend.STATUS_SENT.equals(status)) {
            row.setSentAt(Instant.now());
        }
        try {
            sendRepository.save(row);
        } catch (DataIntegrityViolationException ex) {
            // concurrent tick
        }
    }

    private void maybeSendWhatsApp(
            String businessId,
            MerchantOnboardingStep step,
            MerchantOnboardingGateService.Snapshot snap
    ) {
        if (alreadyHandled(businessId, step, MerchantOnboardingSend.CHANNEL_WHATSAPP)) {
            return;
        }
        long sent = sendRepository.countByBusinessIdAndChannelAndStatus(
                businessId,
                MerchantOnboardingSend.CHANNEL_WHATSAPP,
                MerchantOnboardingSend.STATUS_SENT);
        if (sent >= MAX_WHATSAPP_PER_ENROLLMENT) {
            recordOutcome(businessId, step, MerchantOnboardingSend.CHANNEL_WHATSAPP,
                    MerchantOnboardingSend.STATUS_SKIPPED, "whatsapp_cap");
            return;
        }
        Instant dayStart = LocalDate.now(snap.zone())
                .atStartOfDay(snap.zone())
                .toInstant();
        if (sendRepository.existsSentSince(
                businessId,
                MerchantOnboardingSend.CHANNEL_WHATSAPP,
                MerchantOnboardingSend.STATUS_SENT,
                dayStart)) {
            // Same-day rate limit is transient — do NOT record a skip row, or a
            // later WhatsApp candidate (e.g. a notable N4 web order tomorrow)
            // would be blocked forever by the "any row = handled" rule.
            return;
        }
        Optional<User> owner = resolveOwner(businessId);
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId).orElse(null);
        var rendered = messageRenderer.render(
                step,
                owner.map(User::getName).orElse(null),
                business != null ? business.getName() : null,
                audienceService.shopOrigin(businessId),
                snap);
        if (rendered.whatsAppBody() == null || rendered.whatsAppBody().isBlank()) {
            recordOutcome(businessId, step, MerchantOnboardingSend.CHANNEL_WHATSAPP,
                    MerchantOnboardingSend.STATUS_SKIPPED, "no_whatsapp_copy");
            return;
        }
        if (!opsAlertSettings.shouldAlert(businessId, OpsAlertType.ONBOARDING)) {
            recordOutcome(businessId, step, MerchantOnboardingSend.CHANNEL_WHATSAPP,
                    MerchantOnboardingSend.STATUS_SKIPPED,
                    opsAlertSettings.skipReason(businessId, OpsAlertType.ONBOARDING));
            return;
        }
        try {
            opsAlertDispatcher.dispatch(businessId, OpsAlertType.ONBOARDING, rendered.whatsAppBody());
            recordOutcome(businessId, step, MerchantOnboardingSend.CHANNEL_WHATSAPP,
                    MerchantOnboardingSend.STATUS_SENT, null);
            log.info("onboarding whatsapp dispatched businessId={} step={}", businessId, step.key());
        } catch (RuntimeException ex) {
            log.warn("onboarding whatsapp failed businessId={} step={}", businessId, step.key(), ex);
            recordOutcome(businessId, step, MerchantOnboardingSend.CHANNEL_WHATSAPP,
                    MerchantOnboardingSend.STATUS_SKIPPED, "dispatch_failed");
        }
    }

    private String muteLink(String businessId) {
        String token = muteToken.issue(businessId, Instant.now().plus(Duration.ofDays(30)));
        String base = apiBaseUrl == null || apiBaseUrl.isBlank()
                ? "https://api.kiosk.ke"
                : apiBaseUrl.trim().replaceAll("/$", "");
        return base + "/api/v1/public/onboarding-sequence/mute?token=" + token;
    }

    private Optional<User> resolveOwner(String businessId) {
        return enrollmentRepository.findById(businessId)
                .flatMap(e -> userRepository.findById(e.getOwnerUserId()))
                .filter(u -> u.getDeletedAt() == null)
                .or(() -> {
                    List<User> owners = userRepository.findActiveByRoleKeyOrderByCreatedAtAsc(
                            businessId, IdentityService.OWNER_ROLE_KEY);
                    if (!owners.isEmpty()) {
                        return Optional.of(owners.getFirst());
                    }
                    List<User> admins = userRepository.findActiveByRoleKeyOrderByCreatedAtAsc(
                            businessId, "admin");
                    if (!admins.isEmpty()) {
                        return Optional.of(admins.getFirst());
                    }
                    return Optional.empty();
                });
    }
}
