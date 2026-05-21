package zelisline.ub.notifications.application;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.NotificationEventTypes;
import zelisline.ub.notifications.domain.NotificationEvent;
import zelisline.ub.notifications.repository.NotificationEventRepository;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderRepository;

@Service
@RequiredArgsConstructor
public class NotificationEventProcessor {

    private final NotificationEventRepository eventRepository;
    private final NotificationOrchestrator orchestrator;
    private final StockLowBatchService stockLowBatchService;
    private final WebOrderRepository webOrderRepository;
    private final PromoCampaignDispatchService promoCampaignDispatchService;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processEvent(String eventId) {
        NotificationEvent event = eventRepository.findById(eventId).orElse(null);
        if (event == null || !NotificationEvent.STATUS_PENDING.equals(event.getStatus())) {
            return;
        }
        try {
            switch (event.getEventType()) {
                case NotificationEventTypes.WEB_ORDER_PLACED -> {
                    WebOrder order = loadOrder(event);
                    orchestrator.processWebOrderPlaced(order);
                }
                case NotificationEventTypes.WEB_ORDER_PAID -> {
                    WebOrder order = loadOrder(event);
                    orchestrator.processWebOrderPaid(order);
                }
                case NotificationEventTypes.WEB_ORDER_CONFIRMED -> {
                    WebOrder order = loadOrder(event);
                    orchestrator.processWebOrderConfirmed(order);
                }
                case NotificationEventTypes.WEB_ORDER_DISPATCHED -> {
                    WebOrder order = loadOrder(event);
                    orchestrator.processWebOrderDispatched(order);
                }
                case NotificationEventTypes.WEB_ORDER_DELIVERED -> {
                    WebOrder order = loadOrder(event);
                    orchestrator.processWebOrderDelivered(order);
                }
                case NotificationEventTypes.STOCK_LOW -> {
                    Map<String, String> payload = readPayload(event);
                    stockLowBatchService.accumulate(
                            event.getBusinessId(),
                            payload.get("branchId"),
                            payload.get("itemId"),
                            payload.get("itemName"),
                            payload.get("currentStock"),
                            payload.get("reorderLevel"));
                }
                case NotificationEventTypes.STOCK_LOW_BATCH_FLUSH -> {
                    Map<String, String> payload = readPayload(event);
                    orchestrator.processStockLowBatchFromPayload(event.getBusinessId(), payload);
                }
                case NotificationEventTypes.DAILY_SALES_DIGEST -> {
                    Map<String, String> payload = readPayload(event);
                    orchestrator.processDailySalesDigest(
                            event.getBusinessId(),
                            payload);
                }
                case NotificationEventTypes.ABANDONED_CART_DIGEST -> {
                    Map<String, String> payload = readPayload(event);
                    orchestrator.processAbandonedCartDigest(event.getBusinessId(), payload);
                }
                case NotificationEventTypes.PEAK_HOURS_DIGEST -> {
                    Map<String, String> payload = readPayload(event);
                    orchestrator.processPeakHoursDigest(event.getBusinessId(), payload);
                }
                case NotificationEventTypes.TOP_PRODUCTS_DIGEST -> {
                    Map<String, String> payload = readPayload(event);
                    orchestrator.processTopProductsDigest(event.getBusinessId(), payload);
                }
                case NotificationEventTypes.PROMO_CAMPAIGN_RUN -> {
                    Map<String, String> payload = readPayload(event);
                    String campaignId = payload.getOrDefault("campaignId", event.getAggregateId());
                    promoCampaignDispatchService.processCampaignRun(event.getBusinessId(), campaignId);
                }
                case NotificationEventTypes.WIN_BACK -> {
                    Map<String, String> payload = readPayload(event);
                    String userId = payload.getOrDefault("userId", event.getAggregateId());
                    orchestrator.processWinBack(event.getBusinessId(), userId, payload);
                }
                default -> throw new IllegalStateException("Unknown event type: " + event.getEventType());
            }
            event.setStatus(NotificationEvent.STATUS_PROCESSED);
            event.setProcessedAt(java.time.Instant.now());
            event.setLastError(null);
        } catch (Exception ex) {
            int attempts = event.getAttemptCount() + 1;
            event.setAttemptCount(attempts);
            event.setLastError(truncate(ex.getMessage()));
            if (attempts >= 5) {
                event.setStatus(NotificationEvent.STATUS_FAILED);
            }
        }
        eventRepository.save(event);
    }

    private WebOrder loadOrder(NotificationEvent event) {
        return webOrderRepository.findById(event.getAggregateId())
                .filter(o -> event.getBusinessId().equals(o.getBusinessId()))
                .orElseThrow(() -> new IllegalStateException("Web order not found: " + event.getAggregateId()));
    }

    private Map<String, String> readPayload(NotificationEvent event) {
        try {
            return objectMapper.readValue(event.getPayloadJson(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Invalid event payload", e);
        }
    }

    private static String truncate(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.length() > 480 ? raw.substring(0, 480) : raw;
    }
}
