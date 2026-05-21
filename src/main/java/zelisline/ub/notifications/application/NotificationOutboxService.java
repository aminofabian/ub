package zelisline.ub.notifications.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.NotificationEventTypes;
import zelisline.ub.notifications.domain.NotificationEvent;
import zelisline.ub.notifications.repository.NotificationEventRepository;
import zelisline.ub.storefront.domain.WebOrder;

@Service
@RequiredArgsConstructor
public class NotificationOutboxService {

    private final NotificationEventRepository eventRepository;
    private final NotificationOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    @Value("${app.notifications.outbox.enabled:true}")
    private boolean outboxEnabled;

    @Transactional
    public void enqueueWebOrderPlaced(WebOrder order) {
        if (!outboxEnabled) {
            orchestrator.processWebOrderPlaced(order);
            return;
        }
        enqueue(
                order.getBusinessId(),
                NotificationEventTypes.WEB_ORDER_PLACED,
                "web_order",
                order.getId(),
                "web_order_event:" + order.getId(),
                orderPayload(order));
    }

    @Transactional
    public void enqueueWebOrderConfirmed(WebOrder order) {
        enqueueFulfillmentEvent(order, NotificationEventTypes.WEB_ORDER_CONFIRMED, "web_order_confirmed:");
    }

    @Transactional
    public void enqueueWebOrderDispatched(WebOrder order) {
        enqueueFulfillmentEvent(order, NotificationEventTypes.WEB_ORDER_DISPATCHED, "web_order_dispatched:");
    }

    @Transactional
    public void enqueueWebOrderDelivered(WebOrder order) {
        enqueueFulfillmentEvent(order, NotificationEventTypes.WEB_ORDER_DELIVERED, "web_order_delivered:");
    }

    private void enqueueFulfillmentEvent(WebOrder order, String eventType, String dedupePrefix) {
        if (!outboxEnabled) {
            dispatchFulfillmentSync(order, eventType);
            return;
        }
        enqueue(
                order.getBusinessId(),
                eventType,
                "web_order",
                order.getId(),
                dedupePrefix + order.getId(),
                orderPayload(order));
    }

    private void dispatchFulfillmentSync(WebOrder order, String eventType) {
        switch (eventType) {
            case NotificationEventTypes.WEB_ORDER_CONFIRMED -> orchestrator.processWebOrderConfirmed(order);
            case NotificationEventTypes.WEB_ORDER_DISPATCHED -> orchestrator.processWebOrderDispatched(order);
            case NotificationEventTypes.WEB_ORDER_DELIVERED -> orchestrator.processWebOrderDelivered(order);
            default -> {
            }
        }
    }

    @Transactional
    public void enqueueWebOrderPaid(WebOrder order) {
        if (!outboxEnabled) {
            orchestrator.processWebOrderPaid(order);
            return;
        }
        enqueue(
                order.getBusinessId(),
                NotificationEventTypes.WEB_ORDER_PAID,
                "web_order",
                order.getId(),
                "web_order_paid_event:" + order.getId(),
                orderPayload(order));
    }

    @Transactional
    public void enqueueStockLow(
            String businessId,
            String branchId,
            String itemId,
            String itemName,
            String currentStock,
            String reorderLevel
    ) {
        var payload = new java.util.LinkedHashMap<String, String>();
        payload.put("branchId", branchId);
        payload.put("itemId", itemId);
        payload.put("itemName", itemName);
        payload.put("currentStock", currentStock);
        payload.put("reorderLevel", reorderLevel);
        String dedupe = "stock_low:" + businessId + ":" + branchId + ":" + itemId + ":" + java.time.Instant.now().getEpochSecond() / 60;
        enqueue(
                businessId,
                NotificationEventTypes.STOCK_LOW,
                "item",
                itemId,
                dedupe,
                writeJson(payload));
    }

    @Transactional
    public void enqueueAbandonedCartDigest(String businessId, String reportDay, String cartCount) {
        var payload = new java.util.LinkedHashMap<String, String>();
        payload.put("reportDay", reportDay);
        payload.put("cartCount", cartCount);
        enqueue(
                businessId,
                NotificationEventTypes.ABANDONED_CART_DIGEST,
                "business",
                businessId,
                "abandoned_cart:" + businessId + ":" + reportDay,
                writeJson(payload));
    }

    @Transactional
    public void enqueuePeakHoursDigest(
            String businessId,
            String businessDay,
            String peakHour,
            String revenue,
            String currency
    ) {
        var payload = new java.util.LinkedHashMap<String, String>();
        payload.put("businessDay", businessDay);
        payload.put("peakHour", peakHour);
        payload.put("revenue", revenue);
        payload.put("currency", currency);
        enqueue(
                businessId,
                NotificationEventTypes.PEAK_HOURS_DIGEST,
                "business",
                businessId,
                "peak_hours:" + businessId + ":" + businessDay,
                writeJson(payload));
    }

    @Transactional
    public void enqueueTopProductsDigest(String businessId, String weekEnding, String topItems) {
        var payload = new java.util.LinkedHashMap<String, String>();
        payload.put("weekEnding", weekEnding);
        payload.put("topItems", topItems);
        enqueue(
                businessId,
                NotificationEventTypes.TOP_PRODUCTS_DIGEST,
                "business",
                businessId,
                "top_products:" + businessId + ":" + weekEnding,
                writeJson(payload));
    }

    @Transactional
    public void enqueuePromoCampaignRun(String businessId, String campaignId) {
        var payload = new java.util.LinkedHashMap<String, String>();
        payload.put("campaignId", campaignId);
        enqueue(
                businessId,
                NotificationEventTypes.PROMO_CAMPAIGN_RUN,
                "notification_campaign",
                campaignId,
                "promo_campaign_run:" + campaignId,
                writeJson(payload));
    }

    @Transactional
    public void enqueueWinBack(String businessId, String userId, String dedupeKey) {
        var payload = new java.util.LinkedHashMap<String, String>();
        payload.put("userId", userId);
        payload.put("dedupeKey", dedupeKey);
        enqueue(
                businessId,
                NotificationEventTypes.WIN_BACK,
                "user",
                userId,
                dedupeKey,
                writeJson(payload));
    }

    @Transactional
    public void enqueueDailySalesDigest(String businessId, String businessDay, String revenue, String currency) {
        var payload = new java.util.LinkedHashMap<String, String>();
        payload.put("businessDay", businessDay);
        payload.put("revenue", revenue);
        payload.put("currency", currency);
        payload.put("orderCount", "1");
        enqueue(
                businessId,
                NotificationEventTypes.DAILY_SALES_DIGEST,
                "business",
                businessId,
                "sales_digest:" + businessId + ":" + businessDay,
                writeJson(payload));
    }

    private String orderPayload(WebOrder order) {
        var map = new java.util.LinkedHashMap<String, String>();
        map.put("orderId", order.getId());
        return writeJson(map);
    }

    private void enqueue(
            String businessId,
            String eventType,
            String aggregateType,
            String aggregateId,
            String dedupeKey,
            String payloadJson
    ) {
        if (eventRepository.existsByBusinessIdAndDedupeKey(businessId, dedupeKey)) {
            return;
        }
        NotificationEvent event = new NotificationEvent();
        event.setBusinessId(businessId);
        event.setEventType(eventType);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setDedupeKey(dedupeKey);
        event.setPayloadJson(payloadJson);
        try {
            eventRepository.save(event);
        } catch (DataIntegrityViolationException ignored) {
            // concurrent enqueue
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
