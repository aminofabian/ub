package zelisline.ub.notifications.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CatalogNotificationListener {

    private final NotificationSubscriptionService subscriptionService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onItemRestocked(ItemRestockedEvent event) {
        subscriptionService.notifyBackInStock(event.businessId(), event.itemId(), event.itemName());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPriceDrop(PriceDropForSubscribersEvent event) {
        subscriptionService.notifyPriceDrop(
                event.businessId(),
                event.itemId(),
                event.itemName(),
                event.oldPrice(),
                event.newPrice(),
                event.currency());
    }

    public record ItemRestockedEvent(String businessId, String itemId, String itemName) {
    }

    public record PriceDropForSubscribersEvent(
            String businessId,
            String itemId,
            String itemName,
            String oldPrice,
            String newPrice,
            String currency) {
    }
}
