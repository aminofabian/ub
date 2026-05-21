package zelisline.ub.notifications.application;

import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.NotificationTypes;
import zelisline.ub.storefront.domain.WebOrder;

@Service
@RequiredArgsConstructor
public class NotificationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(NotificationOrchestrator.class);
    private static final String TYPE_STOCK_LOW = "stock.low";
    private static final String TYPE_DAILY_DIGEST = "sales.daily_digest";

    private final NotificationService notificationService;
    private final NotificationTemplateRenderer templateRenderer;
    private final ShopperRecipientResolver shopperRecipientResolver;
    private final NotificationPolicyEngine policyEngine;
    private final ObjectMapper objectMapper;

    public void onWebOrderPlaced(WebOrder order) {
        processWebOrderPlaced(order);
    }

    public void onWebOrderPaid(WebOrder order) {
        processWebOrderPaid(order);
    }

    public void processWebOrderPlaced(WebOrder order) {
        Map<String, String> vars = orderVariables(order);
        notifyStaff(order.getBusinessId(), NotificationTypes.STOREFRONT_ORDER_PLACED, "web_order:" + order.getId(), vars);
        shopperRecipientResolver.resolveBuyerUserId(order.getBusinessId(), order.getCustomerEmail())
                .ifPresent(userId -> notifyShopper(
                        order.getBusinessId(),
                        userId,
                        NotificationTypes.ORDER_RECEIVED,
                        "order_received:" + order.getId(),
                        vars));
    }

    public void processWebOrderPaid(WebOrder order) {
        Map<String, String> vars = orderVariables(order);
        notifyStaff(order.getBusinessId(), NotificationTypes.STOREFRONT_ORDER_PAID, "web_order_paid:" + order.getId(), vars);
        shopperRecipientResolver.resolveBuyerUserId(order.getBusinessId(), order.getCustomerEmail())
                .ifPresent(userId -> notifyShopper(
                        order.getBusinessId(),
                        userId,
                        NotificationTypes.ORDER_PAYMENT_RECEIVED,
                        "order_paid:" + order.getId(),
                        vars));
    }

    public void processWebOrderConfirmed(WebOrder order) {
        notifyShopperFulfillment(order, NotificationTypes.ORDER_CONFIRMED, "order_confirmed:" + order.getId());
    }

    public void processWebOrderDispatched(WebOrder order) {
        notifyShopperFulfillment(order, NotificationTypes.ORDER_DISPATCHED, "order_dispatched:" + order.getId());
    }

    public void processWebOrderDelivered(WebOrder order) {
        notifyShopperFulfillment(order, NotificationTypes.ORDER_DELIVERED, "order_delivered:" + order.getId());
    }

    private void notifyShopperFulfillment(WebOrder order, String type, String dedupeKey) {
        Map<String, String> vars = orderVariables(order);
        shopperRecipientResolver.resolveBuyerUserId(order.getBusinessId(), order.getCustomerEmail())
                .ifPresent(userId -> notifyShopper(order.getBusinessId(), userId, type, dedupeKey, vars));
    }

    /** Called from {@link NotificationEventProcessor} with flush payload containing items. */
    public void processStockLowBatchFromPayload(String businessId, Map<String, String> payload) {
        String branchId = payload.getOrDefault("branchId", "");
        String batchId = payload.getOrDefault("batchId", "");
        List<Map<String, String>> items = parseItems(payload.get("itemsJson"));
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("branchId", branchId);
        vars.put("batchId", batchId);
        vars.put("itemCount", String.valueOf(items.size()));
        vars.put("itemNames", summarizeItemNames(items));
        vars.put("itemsJson", payload.getOrDefault("itemsJson", "[]"));
        String dedupe = "stock_low_batch:" + businessId + ":" + branchId + ":" + batchId;
        notifyStaff(businessId, TYPE_STOCK_LOW, dedupe, vars);
    }

    public void processDailySalesDigest(String businessId, Map<String, String> payload) {
        notifyStaff(
                businessId,
                TYPE_DAILY_DIGEST,
                "sales_digest:" + businessId + ":" + payload.getOrDefault("businessDay", ""),
                payload);
    }

    public void processAbandonedCartDigest(String businessId, Map<String, String> payload) {
        notifyStaff(
                businessId,
                NotificationTypes.ABANDONED_CART,
                "abandoned_cart:" + businessId + ":" + payload.getOrDefault("reportDay", ""),
                payload);
    }

    public void processPeakHoursDigest(String businessId, Map<String, String> payload) {
        notifyStaff(
                businessId,
                NotificationTypes.PEAK_HOURS,
                "peak_hours:" + businessId + ":" + payload.getOrDefault("businessDay", ""),
                payload);
    }

    public void processTopProductsDigest(String businessId, Map<String, String> payload) {
        notifyStaff(
                businessId,
                NotificationTypes.TOP_PRODUCTS,
                "top_products:" + businessId + ":" + payload.getOrDefault("weekEnding", ""),
                payload);
    }

    public void processWinBack(String businessId, String userId, Map<String, String> payload) {
        notifyShopperPromotional(
                businessId,
                userId,
                NotificationTypes.WIN_BACK,
                payload.getOrDefault("dedupeKey", "win_back:" + userId),
                payload);
    }

    private Map<String, String> orderVariables(WebOrder order) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("orderId", order.getId());
        vars.put("customerName", order.getCustomerName() != null ? order.getCustomerName() : "");
        vars.put("total", order.getGrandTotal() != null
                ? order.getGrandTotal().setScale(2, RoundingMode.HALF_UP).toPlainString()
                : "");
        vars.put("currency", order.getCurrency() != null ? order.getCurrency().trim() : "KES");
        vars.put("branchId", order.getCatalogBranchId() != null ? order.getCatalogBranchId() : "");
        return vars;
    }

    private void notifyStaff(String businessId, String type, String dedupeKey, Map<String, String> variables) {
        NotificationTemplateRenderer.RenderedNotification rendered =
                templateRenderer.render(businessId, type, variables);
        String payloadJson = buildPayload(rendered, variables);
        notificationService.tryInsertDedupe(
                businessId,
                type,
                dedupeKey,
                rendered.category(),
                rendered.priority(),
                payloadJson);
    }

    public void notifyShopperPromotional(
            String businessId,
            String userId,
            String type,
            String dedupeKey,
            Map<String, String> variables
    ) {
        notifyShopper(businessId, userId, type, dedupeKey, variables);
    }

    private void notifyShopper(
            String businessId,
            String userId,
            String type,
            String dedupeKey,
            Map<String, String> variables
    ) {
        NotificationTemplateRenderer.RenderedNotification rendered =
                templateRenderer.render(businessId, type, variables);
        if (!policyEngine.mayDeliverToUser(
                businessId,
                userId,
                type,
                rendered.category(),
                rendered.priority(),
                "IN_APP")) {
            return;
        }
        String payloadJson = buildPayload(rendered, variables);
        notificationService.tryInsertDedupeForUser(
                businessId,
                userId,
                type,
                dedupeKey,
                rendered.category(),
                rendered.priority(),
                payloadJson);
    }

    private String buildPayload(
            NotificationTemplateRenderer.RenderedNotification rendered,
            Map<String, String> variables
    ) {
        Map<String, Object> payload = new LinkedHashMap<>(variables);
        payload.put("title", rendered.title());
        payload.put("body", rendered.body());
        payload.put("actionUrl", rendered.actionUrl());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize notification payload", e);
            return "{}";
        }
    }

    private List<Map<String, String>> parseItems(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, String>> items = objectMapper.readValue(itemsJson, new TypeReference<>() {});
            return items != null ? items : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String summarizeItemNames(List<Map<String, String>> items) {
        if (items.isEmpty()) {
            return "";
        }
        return items.stream()
                .map(m -> m.getOrDefault("itemName", ""))
                .filter(s -> !s.isBlank())
                .limit(3)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }
}
