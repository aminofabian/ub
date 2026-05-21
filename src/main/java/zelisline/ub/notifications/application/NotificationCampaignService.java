package zelisline.ub.notifications.application;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.api.dto.CreateNotificationCampaignRequest;
import zelisline.ub.notifications.api.dto.NotificationCampaignResponse;
import zelisline.ub.notifications.domain.NotificationCampaign;
import zelisline.ub.notifications.repository.NotificationCampaignRepository;

@Service
@RequiredArgsConstructor
public class NotificationCampaignService {

    private final NotificationCampaignRepository campaignRepository;
    private final PromoCampaignDispatchService dispatchService;
    private final NotificationOutboxService notificationOutboxService;

    @Value("${app.notifications.outbox.enabled:true}")
    private boolean outboxEnabled;

    @Transactional(readOnly = true)
    public List<NotificationCampaignResponse> list(String businessId) {
        return campaignRepository.findByBusinessIdOrderByCreatedAtDesc(businessId).stream()
                .map(NotificationCampaignService::toDto)
                .toList();
    }

    @Transactional
    public NotificationCampaignResponse create(
            String businessId,
            String createdByUserId,
            CreateNotificationCampaignRequest req
    ) {
        NotificationCampaign campaign = new NotificationCampaign();
        campaign.setBusinessId(businessId);
        campaign.setName(req.name().trim());
        campaign.setCampaignType(normalizeType(req.campaignType()));
        campaign.setTitle(req.title().trim());
        campaign.setBody(req.body().trim());
        campaign.setActionUrl(blankToNull(req.actionUrl()));
        campaign.setRecipientScope(normalizeScope(req.recipientScope()));
        campaign.setCatalogBranchId(resolveBranchId(req.recipientScope(), req.catalogBranchId()));
        campaign.setCreatedByUserId(createdByUserId);
        if (req.scheduledAt() != null) {
            if (!req.scheduledAt().isAfter(Instant.now())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scheduledAt must be in the future");
            }
            campaign.setScheduledAt(req.scheduledAt());
            campaign.setStatus(NotificationCampaign.STATUS_SCHEDULED);
        } else {
            campaign.setStatus(NotificationCampaign.STATUS_DRAFT);
        }
        return toDto(campaignRepository.save(campaign));
    }

    @Transactional
    public NotificationCampaignResponse runNow(String businessId, String campaignId) {
        NotificationCampaign campaign = loadEditable(businessId, campaignId);
        if (NotificationCampaign.STATUS_RUNNING.equals(campaign.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Campaign is already running");
        }
        if (NotificationCampaign.STATUS_COMPLETED.equals(campaign.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Campaign already completed");
        }
        triggerCampaignRun(campaign);
        return toDto(campaignRepository.findById(campaign.getId()).orElse(campaign));
    }

    @Transactional
    public NotificationCampaignResponse cancel(String businessId, String campaignId) {
        NotificationCampaign campaign = loadEditable(businessId, campaignId);
        if (NotificationCampaign.STATUS_RUNNING.equals(campaign.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot cancel a running campaign");
        }
        campaign.setStatus(NotificationCampaign.STATUS_CANCELLED);
        return toDto(campaignRepository.save(campaign));
    }

    @Transactional
    public void dispatchDueScheduledCampaigns() {
        List<NotificationCampaign> due = campaignRepository.findByStatusAndScheduledAtLessThanEqual(
                NotificationCampaign.STATUS_SCHEDULED,
                Instant.now());
        for (NotificationCampaign campaign : due) {
            try {
                triggerCampaignRun(campaign);
            } catch (RuntimeException ex) {
                // logged in dispatch service
            }
        }
    }

    private void triggerCampaignRun(NotificationCampaign campaign) {
        campaign.setStatus(NotificationCampaign.STATUS_RUNNING);
        campaign.setStartedAt(Instant.now());
        campaign.setCompletedAt(null);
        campaignRepository.save(campaign);
        if (outboxEnabled) {
            notificationOutboxService.enqueuePromoCampaignRun(campaign.getBusinessId(), campaign.getId());
        } else {
            dispatchService.processCampaignRun(campaign.getBusinessId(), campaign.getId());
        }
    }

    private NotificationCampaign loadEditable(String businessId, String campaignId) {
        NotificationCampaign campaign = campaignRepository
                .findById(campaignId.trim())
                .filter(c -> businessId.equals(c.getBusinessId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
        if (NotificationCampaign.STATUS_CANCELLED.equals(campaign.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Campaign is cancelled");
        }
        return campaign;
    }

    private static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "campaignType required");
        }
        String t = raw.trim().toUpperCase();
        if (!NotificationCampaign.TYPE_FLASH_SALE.equals(t)
                && !NotificationCampaign.TYPE_WEEKLY_DEALS.equals(t)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported campaignType");
        }
        return t;
    }

    private static String normalizeScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return NotificationCampaign.SCOPE_ALL_BUYERS;
        }
        String s = raw.trim().toUpperCase();
        if (!NotificationCampaign.SCOPE_ALL_BUYERS.equals(s)
                && !NotificationCampaign.SCOPE_ACTIVE_BUYERS_90D.equals(s)
                && !NotificationCampaign.SCOPE_INACTIVE_BUYERS_30D.equals(s)
                && !NotificationCampaign.SCOPE_BRANCH_ACTIVE_BUYERS_90D.equals(s)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported recipientScope");
        }
        return s;
    }

    private static String resolveBranchId(String recipientScope, String catalogBranchId) {
        String scope = recipientScope == null || recipientScope.isBlank()
                ? NotificationCampaign.SCOPE_ALL_BUYERS
                : recipientScope.trim().toUpperCase();
        if (!NotificationCampaign.SCOPE_BRANCH_ACTIVE_BUYERS_90D.equals(scope)) {
            return null;
        }
        if (catalogBranchId == null || catalogBranchId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "catalogBranchId required for BRANCH_ACTIVE_BUYERS_90D");
        }
        return catalogBranchId.trim();
    }

    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    static NotificationCampaignResponse toDto(NotificationCampaign c) {
        return new NotificationCampaignResponse(
                c.getId(),
                c.getName(),
                c.getCampaignType(),
                c.getStatus(),
                c.getTitle(),
                c.getBody(),
                c.getActionUrl(),
                c.getRecipientScope(),
                c.getCatalogBranchId(),
                c.getScheduledAt(),
                c.getStartedAt(),
                c.getCompletedAt(),
                c.getRecipientsTargeted(),
                c.getRecipientsSent(),
                c.getCreatedAt());
    }
}
