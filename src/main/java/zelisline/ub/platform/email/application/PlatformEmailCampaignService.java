package zelisline.ub.platform.email.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.identity.application.AuthRegistrationService;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.platform.email.api.dto.PlatformEmailCampaignDtos.CreatePlatformEmailCampaignRequest;
import zelisline.ub.platform.email.api.dto.PlatformEmailCampaignDtos.PlatformEmailCampaignDetailResponse;
import zelisline.ub.platform.email.api.dto.PlatformEmailCampaignDtos.PlatformEmailCampaignRecipientResponse;
import zelisline.ub.platform.email.api.dto.PlatformEmailCampaignDtos.PlatformEmailCampaignSummaryResponse;
import zelisline.ub.platform.email.api.dto.PlatformEmailCampaignDtos.PlatformEmailPreviewResponse;
import zelisline.ub.platform.email.api.dto.PlatformEmailCampaignDtos.PreviewPlatformEmailRequest;
import zelisline.ub.platform.email.api.dto.PlatformEmailCampaignDtos.SaEmailRecipientResponse;
import zelisline.ub.platform.email.domain.PlatformEmailCampaign;
import zelisline.ub.platform.email.domain.PlatformEmailCampaignRecipient;
import zelisline.ub.platform.email.repository.PlatformEmailCampaignRecipientRepository;
import zelisline.ub.platform.email.repository.PlatformEmailCampaignRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformEmailCampaignService {

    static final String FROM_DISPLAY_NAME = "Kiosk";
    private static final long SEND_PAUSE_MS = 40L;

    private final PlatformEmailAudienceService audienceService;
    private final PlatformEmailCampaignRepository campaignRepository;
    private final PlatformEmailCampaignRecipientRepository recipientRepository;
    private final PlatformCampaignEmailRenderer emailRenderer;
    private final NotificationService notificationService;
    private final AuthRegistrationService authRegistrationService;
    private final UserRepository userRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional(readOnly = true)
    public Page<SaEmailRecipientResponse> listRecipients(
            String segment,
            List<String> businessIds,
            List<String> userIds,
            String q,
            Pageable pageable
    ) {
        List<SaEmailRecipientResponse> all = audienceService.resolve(segment, businessIds, userIds, q);
        return pageOf(all, pageable);
    }

    @Transactional
    public PlatformEmailCampaignDetailResponse createDraft(
            CreatePlatformEmailCampaignRequest request,
            String superAdminId
    ) {
        String segment = audienceService.normalizeSegment(request.segmentKey());
        List<SaEmailRecipientResponse> audience = audienceService.resolve(
                segment, request.businessIds(), request.userIds(), null);
        if (audience.size() > PlatformEmailCampaign.MAX_RECIPIENTS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Campaign exceeds " + PlatformEmailCampaign.MAX_RECIPIENTS + " recipients");
        }
        if (audience.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No recipients match this audience");
        }

        PlatformEmailCampaign campaign = new PlatformEmailCampaign();
        campaign.setName(request.name().trim());
        campaign.setSegmentKey(segment);
        campaign.setSubject(request.subject().trim());
        campaign.setBodyMarkdown(request.bodyMarkdown());
        campaign.setCtaLabel(blankToDefault(request.ctaLabel(), "Continue setup"));
        campaign.setStatus(PlatformEmailCampaign.STATUS_DRAFT);
        campaign.setCreatedBySuperAdminId(superAdminId);
        campaign.setRecipientsTargeted(audience.size());
        campaignRepository.save(campaign);

        int skipped = 0;
        List<PlatformEmailCampaignRecipient> rows = new ArrayList<>();
        for (SaEmailRecipientResponse person : audience) {
            PlatformEmailCampaignRecipient row = new PlatformEmailCampaignRecipient();
            row.setCampaignId(campaign.getId());
            row.setBusinessId(person.businessId());
            row.setUserId(person.userId());
            row.setEmail(person.email() == null ? "" : person.email());
            row.setContinueKind(person.continueKind());
            if (person.skipReason() != null) {
                row.setStatus(PlatformEmailCampaignRecipient.STATUS_SKIPPED);
                row.setError(person.skipReason());
                skipped++;
            } else {
                row.setStatus(PlatformEmailCampaignRecipient.STATUS_PENDING);
            }
            rows.add(row);
        }
        recipientRepository.saveAll(rows);
        campaign.setRecipientsSkipped(skipped);
        campaignRepository.save(campaign);
        return toDetail(campaign, rows);
    }

    @Transactional(readOnly = true)
    public Page<PlatformEmailCampaignSummaryResponse> listCampaigns(Pageable pageable) {
        return campaignRepository.findAllByOrderByCreatedAtDesc(pageable).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public PlatformEmailCampaignDetailResponse getCampaign(String id) {
        PlatformEmailCampaign campaign = requireCampaign(id);
        List<PlatformEmailCampaignRecipient> rows =
                recipientRepository.findByCampaignIdOrderByEmailAsc(campaign.getId());
        return toDetail(campaign, rows);
    }

    @Transactional(readOnly = true)
    public PlatformEmailPreviewResponse previewUnpersisted(PreviewPlatformEmailRequest request) {
        List<SaEmailRecipientResponse> audience = audienceService.resolve(
                request.segmentKey(), request.businessIds(), request.userIds(), null);
        if (audience.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No recipients match this audience");
        }
        SaEmailRecipientResponse sample = pickSample(audience, request.userId());
        return renderPreview(
                request.subject(),
                request.bodyMarkdown(),
                blankToDefault(request.ctaLabel(), "Continue setup"),
                sample,
                audienceService.continueUrlForPreview(sample));
    }

    @Transactional(readOnly = true)
    public PlatformEmailPreviewResponse previewCampaign(String campaignId, String userId) {
        PlatformEmailCampaign campaign = requireCampaign(campaignId);
        List<PlatformEmailCampaignRecipient> rows =
                recipientRepository.findByCampaignIdOrderByEmailAsc(campaign.getId());
        PlatformEmailCampaignRecipient row = pickRecipientRow(rows, userId);
        User user = userRepository.findById(row.getUserId()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipient user no longer exists"));
        SaEmailRecipientResponse sample = new SaEmailRecipientResponse(
                user.getId(),
                row.getEmail(),
                user.getName(),
                "",
                user.getStatus(),
                user.getLastLoginAt(),
                row.getBusinessId(),
                "",
                "",
                "",
                row.getContinueKind(),
                row.getError());
        String continueUrl = PlatformEmailCampaignRecipient.KIND_VERIFY.equals(row.getContinueKind())
                ? audienceService.shopOrigin(row.getBusinessId()) + "/verify-email?token=preview"
                : audienceService.shopOrigin(row.getBusinessId()) + "/business";
        return renderPreview(campaign.getSubject(), campaign.getBodyMarkdown(), campaign.getCtaLabel(), sample, continueUrl);
    }

    public PlatformEmailCampaignDetailResponse send(String campaignId, String superAdminId) {
        PlatformEmailCampaign campaign = requireCampaign(campaignId);
        if (!PlatformEmailCampaign.STATUS_DRAFT.equals(campaign.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This campaign has already been run");
        }
        campaign.setStatus(PlatformEmailCampaign.STATUS_RUNNING);
        campaign.setStartedAt(Instant.now());
        campaignRepository.save(campaign);

        List<PlatformEmailCampaignRecipient> rows =
                recipientRepository.findByCampaignIdOrderByEmailAsc(campaign.getId());
        int sent = 0;
        int failed = 0;
        int skipped = 0;
        boolean firstPending = true;
        for (PlatformEmailCampaignRecipient row : rows) {
            if (PlatformEmailCampaignRecipient.STATUS_SKIPPED.equals(row.getStatus())) {
                skipped++;
                continue;
            }
            if (!firstPending) {
                pauseQuietly();
            }
            firstPending = false;
            try {
                User user = userRepository.findById(row.getUserId()).orElseThrow(
                        () -> new IllegalStateException("User missing"));
                String continueUrl = resolveContinueUrl(user, row);
                SaEmailRecipientResponse person = new SaEmailRecipientResponse(
                        user.getId(),
                        row.getEmail(),
                        user.getName(),
                        "",
                        user.getStatus(),
                        user.getLastLoginAt(),
                        row.getBusinessId(),
                        "",
                        "",
                        "",
                        row.getContinueKind(),
                        null);
                PlatformEmailPreviewResponse rendered = renderPreview(
                        campaign.getSubject(),
                        campaign.getBodyMarkdown(),
                        campaign.getCtaLabel(),
                        person,
                        continueUrl);
                notificationService.sendPlatformCampaignEmail(
                        row.getEmail(),
                        rendered.subject(),
                        rendered.text(),
                        rendered.html(),
                        FROM_DISPLAY_NAME);
                row.setStatus(PlatformEmailCampaignRecipient.STATUS_SENT);
                row.setSentAt(Instant.now());
                row.setError(null);
                sent++;
            } catch (Exception ex) {
                log.warn("platform campaign {} failed for {}: {}", campaign.getId(), row.getEmail(), ex.toString());
                row.setStatus(PlatformEmailCampaignRecipient.STATUS_FAILED);
                row.setError(trimError(ex.getMessage()));
                failed++;
            }
            recipientRepository.save(row);
        }

        campaign.setRecipientsSent(sent);
        campaign.setRecipientsFailed(failed);
        campaign.setRecipientsSkipped(skipped);
        campaign.setCompletedAt(Instant.now());
        campaign.setStatus(sent == 0 && failed > 0
                ? PlatformEmailCampaign.STATUS_FAILED
                : PlatformEmailCampaign.STATUS_COMPLETED);
        campaignRepository.save(campaign);

        auditEventPublisher.publishSynchronous(auditEventBuilder
                .builder(AuditEventCategory.SYSTEM, AuditEventTypes.PLATFORM_EMAIL_CAMPAIGN_SENT, AuditEventSeverity.INFO)
                .businessId("platform")
                .actor(superAdminId, AuditEventActorType.USER)
                .actorName("super_admin")
                .target("platform_email_campaign", campaign.getId())
                .targetLabel(campaign.getName())
                .source("super_admin_portal")
                .reason("targeted=" + campaign.getRecipientsTargeted()
                        + " sent=" + sent
                        + " failed=" + failed
                        + " skipped=" + skipped)
                .build());

        return toDetail(campaign, rows);
    }

    private String resolveContinueUrl(User user, PlatformEmailCampaignRecipient row) {
        if (!PlatformEmailCampaignRecipient.KIND_VERIFY.equals(row.getContinueKind())) {
            return audienceService.continueUrlForSend(user, row.getContinueKind(), null);
        }
        return authRegistrationService.issueVerificationLinkOnly(user);
    }

    private PlatformEmailPreviewResponse renderPreview(
            String subjectTemplate,
            String bodyTemplate,
            String ctaLabel,
            SaEmailRecipientResponse sample,
            String continueUrl
    ) {
        String shopUrl = audienceService.shopOrigin(sample.businessId());
        String businessName = sample.businessName() == null || sample.businessName().isBlank()
                ? sample.slug()
                : sample.businessName();
        PlatformEmailMerge.Result merged = PlatformEmailMerge.apply(
                subjectTemplate,
                bodyTemplate,
                new PlatformEmailMerge.Context(
                        firstName(sample.name()),
                        sample.email(),
                        businessName,
                        shopUrl,
                        continueUrl));
        String html = emailRenderer.renderHtml(
                merged.subject(),
                PlatformEmailMarkdown.toHtml(merged.body()),
                ctaLabel,
                continueUrl,
                shopUrl);
        String text = PlatformEmailMarkdown.toPlainText(merged.body())
                + "\n\n" + ctaLabel + ":\n" + continueUrl;
        return new PlatformEmailPreviewResponse(
                sample.userId(),
                sample.email(),
                merged.subject(),
                html,
                text,
                continueUrl,
                merged.unknownTags());
    }

    private SaEmailRecipientResponse pickSample(List<SaEmailRecipientResponse> audience, String userId) {
        if (userId != null && !userId.isBlank()) {
            return audience.stream()
                    .filter(r -> userId.equals(r.userId()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User is not in this audience"));
        }
        return audience.stream()
                .filter(r -> r.skipReason() == null)
                .findFirst()
                .orElse(audience.getFirst());
    }

    private PlatformEmailCampaignRecipient pickRecipientRow(
            List<PlatformEmailCampaignRecipient> rows, String userId) {
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campaign has no recipients");
        }
        if (userId != null && !userId.isBlank()) {
            return rows.stream()
                    .filter(r -> userId.equals(r.getUserId()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User is not in this campaign"));
        }
        return rows.getFirst();
    }

    private PlatformEmailCampaign requireCampaign(String id) {
        return campaignRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
    }

    private PlatformEmailCampaignSummaryResponse toSummary(PlatformEmailCampaign campaign) {
        return new PlatformEmailCampaignSummaryResponse(
                campaign.getId(),
                campaign.getName(),
                campaign.getSegmentKey(),
                campaign.getSubject(),
                campaign.getStatus(),
                campaign.getRecipientsTargeted(),
                campaign.getRecipientsSent(),
                campaign.getRecipientsFailed(),
                campaign.getRecipientsSkipped(),
                campaign.getCreatedAt(),
                campaign.getStartedAt(),
                campaign.getCompletedAt());
    }

    private PlatformEmailCampaignDetailResponse toDetail(
            PlatformEmailCampaign campaign,
            List<PlatformEmailCampaignRecipient> rows
    ) {
        return new PlatformEmailCampaignDetailResponse(
                campaign.getId(),
                campaign.getName(),
                campaign.getSegmentKey(),
                campaign.getSubject(),
                campaign.getBodyMarkdown(),
                campaign.getCtaLabel(),
                campaign.getStatus(),
                campaign.getRecipientsTargeted(),
                campaign.getRecipientsSent(),
                campaign.getRecipientsFailed(),
                campaign.getRecipientsSkipped(),
                campaign.getCreatedAt(),
                campaign.getStartedAt(),
                campaign.getCompletedAt(),
                rows.stream().map(this::toRecipient).toList());
    }

    private PlatformEmailCampaignRecipientResponse toRecipient(PlatformEmailCampaignRecipient row) {
        return new PlatformEmailCampaignRecipientResponse(
                row.getId(),
                row.getBusinessId(),
                row.getUserId(),
                row.getEmail(),
                row.getContinueKind(),
                row.getStatus(),
                row.getError(),
                row.getSentAt());
    }

    private static <T> Page<T> pageOf(List<T> all, Pageable pageable) {
        int start = (int) pageable.getOffset();
        if (start >= all.size()) {
            return new PageImpl<>(List.of(), pageable, all.size());
        }
        int end = Math.min(start + pageable.getPageSize(), all.size());
        return new PageImpl<>(all.subList(start, end), pageable, all.size());
    }

    private static String blankToDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return name.trim().split("\\s+")[0];
    }

    private static String trimError(String message) {
        if (message == null || message.isBlank()) {
            return "send_failed";
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private static void pauseQuietly() {
        try {
            Thread.sleep(SEND_PAUSE_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
