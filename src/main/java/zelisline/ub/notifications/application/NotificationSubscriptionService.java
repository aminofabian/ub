package zelisline.ub.notifications.application;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.notifications.NotificationTypes;
import zelisline.ub.notifications.api.dto.NotificationSubscriptionResponse;
import zelisline.ub.notifications.domain.NotificationSubscription;
import zelisline.ub.notifications.repository.NotificationSubscriptionRepository;

@Service
@RequiredArgsConstructor
public class NotificationSubscriptionService {

    private final NotificationSubscriptionRepository subscriptionRepository;
    private final NotificationOrchestrator orchestrator;
    private final NotificationPolicyEngine policyEngine;
    private final ItemRepository itemRepository;

    @Transactional
    public NotificationSubscriptionResponse subscribe(
            String businessId,
            String userId,
            String itemId,
            String kind
    ) {
        String normalizedKind = normalizeKind(kind);
        itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
        NotificationSubscription sub = subscriptionRepository
                .findByBusinessIdAndUserIdAndItemIdAndKind(businessId, userId, itemId, normalizedKind)
                .orElseGet(() -> {
                    NotificationSubscription row = new NotificationSubscription();
                    row.setBusinessId(businessId);
                    row.setUserId(userId);
                    row.setItemId(itemId);
                    row.setKind(normalizedKind);
                    return row;
                });
        sub.setActive(true);
        subscriptionRepository.save(sub);
        return toDto(sub);
    }

    @Transactional
    public void unsubscribe(String businessId, String userId, String itemId, String kind) {
        subscriptionRepository
                .findByBusinessIdAndUserIdAndItemIdAndKind(businessId, userId, itemId, normalizeKind(kind))
                .ifPresent(sub -> {
                    sub.setActive(false);
                    subscriptionRepository.save(sub);
                });
    }

    @Transactional(readOnly = true)
    public List<NotificationSubscriptionResponse> listForUser(String businessId, String userId) {
        return subscriptionRepository.findByBusinessIdAndUserIdAndActiveTrue(businessId, userId).stream()
                .map(NotificationSubscriptionService::toDto)
                .toList();
    }

    @Transactional
    public void notifyBackInStock(String businessId, String itemId, String itemName) {
        List<NotificationSubscription> subs = subscriptionRepository.findByBusinessIdAndItemIdAndKindAndActiveTrue(
                businessId, itemId, NotificationSubscription.KIND_BACK_IN_STOCK);
        for (NotificationSubscription sub : subs) {
            if (!policyEngine.mayDeliverToUser(
                    businessId,
                    sub.getUserId(),
                    NotificationTypes.BACK_IN_STOCK,
                    policyEngine.resolveCategory(businessId, NotificationTypes.BACK_IN_STOCK),
                    "MEDIUM",
                    "IN_APP")) {
                continue;
            }
            Map<String, String> vars = Map.of(
                    "itemId", itemId,
                    "itemName", itemName != null ? itemName : "");
            orchestrator.notifyShopperPromotional(
                    businessId,
                    sub.getUserId(),
                    NotificationTypes.BACK_IN_STOCK,
                    "back_in_stock:" + itemId + ":" + sub.getUserId(),
                    vars);
        }
    }

    @Transactional
    public void notifyPriceDrop(
            String businessId,
            String itemId,
            String itemName,
            String oldPrice,
            String newPrice,
            String currency
    ) {
        List<NotificationSubscription> subs = subscriptionRepository.findByBusinessIdAndItemIdAndKindAndActiveTrue(
                businessId, itemId, NotificationSubscription.KIND_PRICE_DROP);
        for (NotificationSubscription sub : subs) {
            if (!policyEngine.mayDeliverToUser(
                    businessId,
                    sub.getUserId(),
                    NotificationTypes.PRICE_DROP,
                    policyEngine.resolveCategory(businessId, NotificationTypes.PRICE_DROP),
                    "LOW",
                    "IN_APP")) {
                continue;
            }
            Map<String, String> vars = Map.of(
                    "itemId", itemId,
                    "itemName", itemName != null ? itemName : "",
                    "oldPrice", oldPrice,
                    "newPrice", newPrice,
                    "currency", currency != null ? currency : "KES");
            orchestrator.notifyShopperPromotional(
                    businessId,
                    sub.getUserId(),
                    NotificationTypes.PRICE_DROP,
                    "price_drop:" + itemId + ":" + newPrice + ":" + sub.getUserId(),
                    vars);
        }
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind required");
        }
        String k = kind.trim().toUpperCase();
        if (!NotificationSubscription.KIND_BACK_IN_STOCK.equals(k)
                && !NotificationSubscription.KIND_PRICE_DROP.equals(k)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported subscription kind");
        }
        return k;
    }

    private static NotificationSubscriptionResponse toDto(NotificationSubscription sub) {
        return new NotificationSubscriptionResponse(
                sub.getId(),
                sub.getItemId(),
                sub.getKind(),
                sub.isActive(),
                sub.getCreatedAt());
    }
}
