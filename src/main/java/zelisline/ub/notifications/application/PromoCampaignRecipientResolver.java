package zelisline.ub.notifications.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.notifications.domain.NotificationCampaign;
import zelisline.ub.storefront.repository.WebOrderRepository;

@Component
@RequiredArgsConstructor
public class PromoCampaignRecipientResolver {

    private static final String BUYER_ROLE_KEY = "buyer";
    private static final int INACTIVE_DAYS = 30;
    private static final int ACTIVE_DAYS = 90;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ShopperRecipientResolver shopperRecipientResolver;
    private final WebOrderRepository webOrderRepository;

    public List<String> resolveBuyerUserIds(
            String businessId,
            String recipientScope,
            String catalogBranchId
    ) {
        if (NotificationCampaign.SCOPE_INACTIVE_BUYERS_30D.equals(recipientScope)) {
            return resolveInactiveBuyerUserIds(businessId);
        }
        if (NotificationCampaign.SCOPE_BRANCH_ACTIVE_BUYERS_90D.equals(recipientScope)) {
            return resolveBranchActiveBuyerUserIds(businessId, catalogBranchId);
        }
        if (NotificationCampaign.SCOPE_ACTIVE_BUYERS_90D.equals(recipientScope)) {
            return resolveActiveBuyerUserIds(businessId);
        }
        return resolveAllBuyerUserIds(businessId);
    }

    private List<String> resolveAllBuyerUserIds(String businessId) {
        return roleRepository.findSystemRoleByKey(BUYER_ROLE_KEY)
                .map(role -> userRepository.findBuyerUserIdsByBusinessIdAndRoleId(businessId, role.getId()))
                .orElse(List.of());
    }

    private List<String> resolveActiveBuyerUserIds(String businessId) {
        Instant since = Instant.now().minus(ACTIVE_DAYS, ChronoUnit.DAYS);
        return emailsToBuyerUserIds(
                businessId,
                webOrderRepository.findRecentShopperEmails(businessId, since));
    }

    private List<String> resolveBranchActiveBuyerUserIds(String businessId, String catalogBranchId) {
        if (catalogBranchId == null || catalogBranchId.isBlank()) {
            return List.of();
        }
        Instant since = Instant.now().minus(ACTIVE_DAYS, ChronoUnit.DAYS);
        return emailsToBuyerUserIds(
                businessId,
                webOrderRepository.findRecentShopperEmailsByBranch(
                        businessId,
                        catalogBranchId.trim(),
                        since));
    }

    private List<String> resolveInactiveBuyerUserIds(String businessId) {
        Instant cutoff = Instant.now().minus(INACTIVE_DAYS, ChronoUnit.DAYS);
        Set<String> userIds = new LinkedHashSet<>();
        for (WebOrderRepository.InactiveShopperEmail row :
                webOrderRepository.findInactiveShopperEmails(businessId, cutoff)) {
            shopperRecipientResolver
                    .resolveBuyerUserId(businessId, row.getEmail())
                    .ifPresent(userIds::add);
        }
        return new ArrayList<>(userIds);
    }

    private List<String> emailsToBuyerUserIds(
            String businessId,
            List<WebOrderRepository.RecentShopperEmail> rows
    ) {
        Set<String> userIds = new LinkedHashSet<>();
        for (WebOrderRepository.RecentShopperEmail row : rows) {
            shopperRecipientResolver
                    .resolveBuyerUserId(businessId, row.getEmail())
                    .ifPresent(userIds::add);
        }
        return new ArrayList<>(userIds);
    }
}
