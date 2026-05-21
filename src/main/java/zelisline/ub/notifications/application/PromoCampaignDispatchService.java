package zelisline.ub.notifications.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.NotificationTypes;
import zelisline.ub.notifications.domain.NotificationCampaign;
import zelisline.ub.notifications.repository.NotificationCampaignRepository;

@Service
@RequiredArgsConstructor
public class PromoCampaignDispatchService {

    private static final Logger log = LoggerFactory.getLogger(PromoCampaignDispatchService.class);

    private final NotificationCampaignRepository campaignRepository;
    private final PromoCampaignRecipientResolver recipientResolver;
    private final NotificationOrchestrator orchestrator;
    private final NotificationPolicyEngine policyEngine;

    @Transactional
    public void processCampaignRun(String businessId, String campaignId) {
        NotificationCampaign campaign = campaignRepository
                .findById(campaignId)
                .filter(c -> businessId.equals(c.getBusinessId()))
                .orElse(null);
        if (campaign == null || !NotificationCampaign.STATUS_RUNNING.equals(campaign.getStatus())) {
            return;
        }
        String notificationType = notificationTypeFor(campaign.getCampaignType());
        List<String> userIds = recipientResolver.resolveBuyerUserIds(
                businessId,
                campaign.getRecipientScope(),
                campaign.getCatalogBranchId());
        campaign.setRecipientsTargeted(userIds.size());
        int sent = 0;
        Map<String, String> vars = Map.of(
                "title", campaign.getTitle(),
                "body", campaign.getBody(),
                "actionUrl", campaign.getActionUrl() != null ? campaign.getActionUrl() : "/shop",
                "campaignId", campaign.getId(),
                "campaignName", campaign.getName());
        String category = policyEngine.resolveCategory(businessId, notificationType);
        for (String userId : userIds) {
            if (!policyEngine.mayDeliverToUser(
                    businessId,
                    userId,
                    notificationType,
                    category,
                    "LOW",
                    "IN_APP")) {
                continue;
            }
            String dedupeKey = "campaign:" + campaign.getId() + ":user:" + userId;
            orchestrator.notifyShopperPromotional(
                    businessId,
                    userId,
                    notificationType,
                    dedupeKey,
                    vars);
            sent++;
        }
        campaign.setRecipientsSent(sent);
        campaign.setStatus(NotificationCampaign.STATUS_COMPLETED);
        campaign.setCompletedAt(Instant.now());
        campaignRepository.save(campaign);
        log.info(
                "Promo campaign completed id={} business={} targeted={} sent={}",
                campaign.getId(),
                businessId,
                userIds.size(),
                sent);
    }

    private static String notificationTypeFor(String campaignType) {
        if (NotificationCampaign.TYPE_WEEKLY_DEALS.equals(campaignType)) {
            return NotificationTypes.WEEKLY_DEALS;
        }
        return NotificationTypes.FLASH_SALE;
    }
}
