package zelisline.ub.platform.realtime;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.notifications.domain.Notification;
import zelisline.ub.notifications.repository.NotificationRepository;
import zelisline.ub.notifications.application.NotificationPreferenceService;

/**
 * Bridges committed business events to WebSocket fan-out.
 *
 * <p>Listens for Spring application events after transaction commit
 * and pushes typed frames to the appropriate WebSocket sessions.
 */
@Component
public class RealtimeBridge {

    private static final Logger log = LoggerFactory.getLogger(RealtimeBridge.class);

    private final SessionRegistry sessionRegistry;
    private final RealtimeWebSocketHandler handler;
    private final ObjectMapper objectMapper;
    private final NotificationPreferenceService preferenceService;
    private final NotificationRepository notificationRepository;

    public RealtimeBridge(
            SessionRegistry sessionRegistry,
            RealtimeWebSocketHandler handler,
            NotificationPreferenceService preferenceService,
            NotificationRepository notificationRepository
    ) {
        this.sessionRegistry = sessionRegistry;
        this.handler = handler;
        this.objectMapper = new ObjectMapper();
        this.preferenceService = preferenceService;
        this.notificationRepository = notificationRepository;
    }

    // ═══════════════════════════════════════════════════════════════
    // Slice 1 — Notification fan-out
    // ═══════════════════════════════════════════════════════════════

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        Notification notification = event.notification();
        String businessId = notification.getBusinessId();
        String targetUserId = notification.getUserId();
        String eventId = UUID.randomUUID().toString();
        Instant eventTime = notification.getCreatedAt();

        String priority = resolveNotificationPriority(notification.getType());

        // Skip push if target user is in quiet hours
        if (targetUserId != null && preferenceService.isInQuietHours(businessId, targetUserId, "HIGH".equals(priority))) {
            log.debug("Notification suppressed (quiet hours): type={} user={}", notification.getType(), targetUserId);
            return;
        }

        String payloadJson = toJson(buildNotificationPayload(notification));
        if (payloadJson == null) return;

        if (targetUserId != null && !targetUserId.isBlank()) {
            Set<String> sessionIds = sessionRegistry.findSessionsByUser(businessId, targetUserId);
            for (String sid : sessionIds) {
                handler.sendFrame(sid, "notification.created", eventId, priority, eventTime, payloadJson);
            }
            log.debug("Notification fan-out: type={} targetUser={} sessions={}",
                    notification.getType(), targetUserId, sessionIds.size());
        } else {
            Set<String> allSessions = sessionRegistry.findAllSessionsForBusiness(businessId);
            for (String sid : allSessions) {
                handler.sendFrame(sid, "notification.created", eventId, priority, eventTime, payloadJson);
            }
            log.debug("Notification fan-out (business-wide): type={} business={} sessions={}",
                    notification.getType(), businessId, allSessions.size());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationRead(NotificationReadEvent event) {
        String eventId = UUID.randomUUID().toString();
        String payloadJson = "{\"notificationId\":\"" + RealtimeWebSocketHandler.escapeJson(event.notificationId()) + "\"}";
        Set<String> sessionIds = sessionRegistry.findSessionsByUser(event.businessId(), event.userId());
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "notification.read", eventId, "LOW", Instant.now(), payloadJson);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Slice 2 — POS-critical events (ephemeral, no notification row)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fan-out stock.depleted to all cashiers on the branch when a batch hits zero.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStockDepleted(StockDepletedEvent event) {
        String eventId = UUID.randomUUID().toString();
        String payloadJson = toJson(Map.of(
                "itemId", event.itemId(),
                "itemName", event.itemName(),
                "currentStock", "0",
                "batchId", event.batchId()
        ));
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchChannel(
                event.businessId(), event.branchId(), "pos");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "stock.depleted", eventId, "HIGH", Instant.now(), payloadJson);
        }
        log.debug("POS event stock.depleted: item={} branch={} sessions={}",
                event.itemId(), event.branchId(), sessionIds.size());
    }

    /**
     * Fan-out price.changed to all cashiers on the branch.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPriceChanged(PriceChangedEvent event) {
        String eventId = UUID.randomUUID().toString();
        String payloadJson = toJson(Map.of(
                "itemId", event.itemId(),
                "itemName", event.itemName(),
                "oldPrice", event.oldPrice().toPlainString(),
                "newPrice", event.newPrice().toPlainString()
        ));
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchChannel(
                event.businessId(), event.branchId(), "pos");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "price.changed", eventId, "HIGH", Instant.now(), payloadJson);
        }
        log.debug("POS event price.changed: item={} branch={} sessions={}",
                event.itemId(), event.branchId(), sessionIds.size());
    }

    /**
     * Fan-out payment.confirmed to the originating cashier.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStkPaymentSettled(StkPaymentSettledEvent event) {
        String eventId = UUID.randomUUID().toString();
        String payloadJson = toJson(Map.of(
                "checkoutRequestId", event.checkoutRequestId() != null ? event.checkoutRequestId() : "",
                "merchantReference", event.merchantReference() != null ? event.merchantReference() : "",
                "contextType", event.contextType() != null ? event.contextType() : "",
                "contextId", event.contextId() != null ? event.contextId() : "",
                "success", event.success(),
                "message", event.message() != null ? event.message() : ""
        ));
        if (payloadJson == null) {
            return;
        }

        Set<String> sessionIds = sessionRegistry.findAllSessionsForBusiness(event.businessId());
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "stk.payment.settled", eventId, "HIGH", Instant.now(), payloadJson);
        }
        log.debug("POS event stk.payment.settled: checkout={} success={} sessions={}",
                event.checkoutRequestId(), event.success(), sessionIds.size());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        String eventId = UUID.randomUUID().toString();
        String payloadJson = toJson(Map.of(
                "saleId", event.saleId(),
                "amount", event.amount().toPlainString(),
                "paymentMethod", event.paymentMethod()
        ));
        if (payloadJson == null) return;

        // Target the originating cashier specifically
        if (event.cashierUserId() != null) {
            Set<String> sessionIds = sessionRegistry.findSessionsByUser(
                    event.businessId(), event.cashierUserId());
            for (String sid : sessionIds) {
                handler.sendFrame(sid, "payment.confirmed", eventId, "HIGH", Instant.now(), payloadJson);
            }
            log.debug("POS event payment.confirmed: sale={} cashier={} sessions={}",
                    event.saleId(), event.cashierUserId(), sessionIds.size());
        }
    }

    /**
     * Fan-out approval.requested to all users with approve permission on the branch.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApprovalRequested(ApprovalRequestedEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, String>();
        dataMap.put("approvalId", event.approvalId());
        dataMap.put("type", event.adjustmentType());
        dataMap.put("requestedBy", event.requestedBy());
        dataMap.put("itemId", event.itemId());
        dataMap.put("itemName", event.itemName());
        dataMap.put("quantity", event.quantity().toPlainString());
        dataMap.put("reason", event.reason() != null ? event.reason() : "");
        dataMap.put("actionUrl", "/approvals/" + event.approvalId());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchChannel(
                event.businessId(), event.branchId(), "approvals");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "approval.requested", eventId, "HIGH", Instant.now(), payloadJson);
        }
        log.debug("POS event approval.requested: id={} branch={} sessions={}",
                event.approvalId(), event.branchId(), sessionIds.size());
    }

    /**
     * Fan-out approval.resolved back to the requesting cashier.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApprovalResolved(ApprovalResolvedEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, String>();
        dataMap.put("approvalId", event.approvalId());
        dataMap.put("status", event.status());
        dataMap.put("resolvedBy", event.resolvedBy());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;

        if (event.requestedByUserId() != null) {
            Set<String> sessionIds = sessionRegistry.findSessionsByUser(
                    event.businessId(), event.requestedByUserId());
            for (String sid : sessionIds) {
                handler.sendFrame(sid, "approval.resolved", eventId, "HIGH", Instant.now(), payloadJson);
            }
            log.debug("POS event approval.resolved: id={} status={} requestorSessions={}",
                    event.approvalId(), event.status(), sessionIds.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 2 — Inventory, transfer, and shift events
    // ═══════════════════════════════════════════════════════════════

    /**
     * Phase 9: Fan-out transfer.sent when goods leave the source branch (draft → in_transit).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransferSent(TransferSentEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, String>();
        dataMap.put("transferId", event.transferId());
        dataMap.put("fromBranchId", event.fromBranchId());
        dataMap.put("toBranchId", event.toBranchId());
        dataMap.put("itemCount", String.valueOf(event.itemCount()));
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchChannel(
                event.businessId(), event.toBranchId(), "transfers");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "transfer.sent", eventId, "MEDIUM", Instant.now(), payloadJson);
        }
        log.debug("Phase9 event transfer.sent: id={} toBranch={} sessions={}",
                event.transferId(), event.toBranchId(), sessionIds.size());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransferInitiated(TransferInitiatedEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, String>();
        dataMap.put("transferId", event.transferId());
        dataMap.put("fromBranchId", event.fromBranchId());
        dataMap.put("toBranchId", event.toBranchId());
        dataMap.put("itemCount", String.valueOf(event.itemCount()));
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchChannel(
                event.businessId(), event.toBranchId(), "transfers");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "transfer.initiated", eventId, "MEDIUM", Instant.now(), payloadJson);
        }
        log.debug("Phase2 event transfer.initiated: id={} toBranch={} sessions={}",
                event.transferId(), event.toBranchId(), sessionIds.size());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransferReceived(TransferReceivedEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, String>();
        dataMap.put("transferId", event.transferId());
        dataMap.put("fromBranchId", event.fromBranchId());
        dataMap.put("toBranchId", event.toBranchId());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchChannel(
                event.businessId(), event.fromBranchId(), "transfers");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "transfer.received", eventId, "MEDIUM", Instant.now(), payloadJson);
        }
        log.debug("Phase2 event transfer.received: id={} fromBranch={} sessions={}",
                event.transferId(), event.fromBranchId(), sessionIds.size());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShiftOpened(ShiftOpenedEvent event) {
        String eventId = UUID.randomUUID().toString();
        String payloadJson = toJson(Map.of(
                "shiftId", event.shiftId(),
                "branchId", event.branchId(),
                "openedBy", event.openedBy(),
                "openingCash", event.openingCash().toPlainString()
        ));
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchChannel(
                event.businessId(), event.branchId(), "pos");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "shift.opened", eventId, "MEDIUM", Instant.now(), payloadJson);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShiftClosed(ShiftClosedEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, String>();
        dataMap.put("shiftId", event.shiftId());
        dataMap.put("branchId", event.branchId());
        dataMap.put("closedBy", event.closedBy());
        dataMap.put("expectedCash", event.expectedCash().toPlainString());
        dataMap.put("countedCash", event.countedCash().toPlainString());
        dataMap.put("variance", event.variance().toPlainString());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchChannel(
                event.businessId(), event.branchId(), "pos");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "shift.closed", eventId, "MEDIUM", Instant.now(), payloadJson);
        }
        // Also fan out as a notification if variance is significant
        if (event.variance().abs().compareTo(new java.math.BigDecimal("1.00")) > 0) {
            for (String sid : sessionIds) {
                handler.sendFrame(sid, "shift.variance_detected", eventId, "HIGH", Instant.now(), payloadJson);
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStockAdjusted(StockAdjustedEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, String>();
        dataMap.put("itemId", event.itemId());
        dataMap.put("itemName", event.itemName());
        dataMap.put("adjustmentType", event.adjustmentType());
        dataMap.put("quantityDelta", event.quantityDelta().toPlainString());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchChannel(
                event.businessId(), event.branchId(), "stock");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "stock.adjusted", eventId, "MEDIUM", Instant.now(), payloadJson);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStockLow(StockLowEvent event) {
        String eventId = UUID.randomUUID().toString();
        String payloadJson = toJson(Map.of(
                "itemId", event.itemId(),
                "itemName", event.itemName(),
                "currentStock", event.currentStock().toPlainString(),
                "reorderLevel", event.reorderLevel().toPlainString()
        ));
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchChannel(
                event.businessId(), event.branchId(), "stock");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "stock.low", eventId, "HIGH", Instant.now(), payloadJson);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSystemAnnouncement(SystemAnnouncementEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, String>();
        dataMap.put("title", event.title());
        dataMap.put("body", event.body());
        dataMap.put("level", event.level());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;

        Set<String> allSessions = sessionRegistry.findAllSessionsForBusiness(event.businessId());
        for (String sid : allSessions) {
            handler.sendFrame(sid, "system.announcement", eventId,
                    "INFO".equals(event.level()) ? "MEDIUM" : "HIGH",
                    Instant.now(), payloadJson);
        }
        log.info("System announcement: business={} title={} sessions={}",
                event.businessId(), event.title(), allSessions.size());
    }

    // ═══════════════════════════════════════════════════════════════
    // Grocery Checkout Event Listeners
    // ═══════════════════════════════════════════════════════════════

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGroceryInvoiceCreated(GroceryInvoiceCreatedEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, String>();
        dataMap.put("invoiceId", event.invoiceId());
        dataMap.put("barcodeCode", event.barcodeCode());
        dataMap.put("grandTotal", event.grandTotal().toPlainString());
        dataMap.put("lineCount", String.valueOf(event.lineCount()));
        dataMap.put("createdBy", event.createdBy());
        dataMap.put("createdByName", event.createdByName());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;

        // Fan-out to grocery channel subscribers
        Set<String> sessionIds = sessionRegistry.findSessionsByBranchChannel(
                event.businessId(), event.branchId(), "grocery");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "grocery.invoice.created", eventId, "HIGH", Instant.now(), payloadJson);
        }
        log.debug("Grocery event invoice.created: invoice={} branch={} sessions={}",
                event.invoiceId(), event.branchId(), sessionIds.size());

        // Also persist a Notification row so it appears in the bell + REST polling
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("invoiceId", event.invoiceId());
            payload.put("barcodeCode", event.barcodeCode());
            payload.put("grandTotal", event.grandTotal().toPlainString());
            payload.put("lineCount", event.lineCount());
            payload.put("createdByName", event.createdByName());
            payload.put("actionUrl", "/cashier?invoice=" + event.barcodeCode());

            Notification notif = new Notification();
            notif.setBusinessId(event.businessId());
            notif.setUserId(null); // business-wide — all cashiers see it
            notif.setType("grocery.invoice.created");
            notif.setCategory("operational");
            notif.setPriority("HIGH");
            notif.setDedupeKey("gi:" + event.invoiceId());
            notif.setPayloadJson(objectMapper.writeValueAsString(payload));
            notificationRepository.save(notif);
        } catch (Exception e) {
            log.warn("Failed to persist grocery notification for invoice {}", event.invoiceId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGroceryInvoiceLocked(GroceryInvoiceLockedEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, String>();
        dataMap.put("invoiceId", event.invoiceId());
        dataMap.put("barcodeCode", event.barcodeCode());
        dataMap.put("lockedBy", event.lockedBy());
        dataMap.put("lockedByName", event.lockedByName());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchChannel(
                event.businessId(), event.branchId(), "grocery");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "grocery.invoice.locked", eventId, "MEDIUM", Instant.now(), payloadJson);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGroceryInvoiceUnlocked(GroceryInvoiceUnlockedEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, String>();
        dataMap.put("invoiceId", event.invoiceId());
        dataMap.put("barcodeCode", event.barcodeCode());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchChannel(
                event.businessId(), event.branchId(), "grocery");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "grocery.invoice.unlocked", eventId, "LOW", Instant.now(), payloadJson);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGroceryInvoicePaid(GroceryInvoicePaidEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, String>();
        dataMap.put("invoiceId", event.invoiceId());
        dataMap.put("barcodeCode", event.barcodeCode());
        dataMap.put("saleId", event.saleId());
        dataMap.put("paidBy", event.paidBy());
        dataMap.put("paidByName", event.paidByName());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchChannel(
                event.businessId(), event.branchId(), "grocery");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "grocery.invoice.paid", eventId, "HIGH", Instant.now(), payloadJson);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGroceryInvoiceCancelled(GroceryInvoiceCancelledEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, String>();
        dataMap.put("invoiceId", event.invoiceId());
        dataMap.put("barcodeCode", event.barcodeCode());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchChannel(
                event.businessId(), event.branchId(), "grocery");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "grocery.invoice.cancelled", eventId, "MEDIUM", Instant.now(), payloadJson);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGroceryInvoiceExpired(GroceryInvoiceExpiredEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, String>();
        dataMap.put("invoiceId", event.invoiceId());
        dataMap.put("barcodeCode", event.barcodeCode());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchChannel(
                event.businessId(), event.branchId(), "grocery");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "grocery.invoice.expired", eventId, "LOW", Instant.now(), payloadJson);
        }
    }

private String resolveNotificationPriority(String type) {
        return switch (type) {
            case "stock.low", "shift.variance_detected", "storefront.order.placed",
                 "storefront.order.paid", "approval.requested", "approval.resolved",
                 "order.received", "order.payment_received", "order.confirmed",
                 "order.dispatched", "order.delivered" -> "HIGH";
            case "payable.overdue", "receivable.overdue", "batch.expiring" -> "MEDIUM";
            case "credit_sale.reminder" -> "HIGH";
            case "export.completed" -> "LOW";
            default -> "MEDIUM";
        };
    }

    private Map<String, Object> buildNotificationPayload(Notification n) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("id", n.getId());
        payload.put("notificationType", n.getType());
        payload.put("createdAt", n.getCreatedAt().toString());

        if (n.getPayloadJson() != null && !n.getPayloadJson().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                var parsed = objectMapper.readValue(n.getPayloadJson(), Map.class);
                payload.put("payload", parsed);
            } catch (Exception e) {
                payload.put("payload", n.getPayloadJson());
            }
        }

        String title;
        String body = "";
        String actionUrl = "";
        if (usesPayloadPresentation(n.getType()) && n.getPayloadJson() != null && !n.getPayloadJson().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                var parsed = objectMapper.readValue(n.getPayloadJson(), Map.class);
                title = stringOr(parsed.get("title"), defaultTitleForType(n.getType()));
                body = stringOr(parsed.get("body"), "");
                actionUrl = stringOr(parsed.get("actionUrl"),
                        stringOr(parsed.get("paymentUrl"), ""));
            } catch (Exception e) {
                title = defaultTitleForType(n.getType());
            }
        } else {
            title = defaultTitleForType(n.getType());
        }
        payload.put("title", title);
        payload.put("body", body);
        payload.put("actionUrl", actionUrl);

        return payload;
    }

    private static boolean usesPayloadPresentation(String type) {
        return switch (type) {
            case "credit_sale.reminder", "order.received", "order.payment_received",
                 "order.confirmed", "order.dispatched", "order.delivered",
                 "storefront.order.placed", "storefront.order.paid", "stock.low",
                 "sales.daily_digest" -> true;
            default -> false;
        };
    }

    private static String defaultTitleForType(String type) {
        return switch (type) {
            case "payable.overdue" -> "Overdue supplier payments";
            case "receivable.overdue" -> "Overdue customer payments";
            case "shift.variance_detected" -> "Shift cash variance detected";
            case "stock.low" -> "Low stock alert";
            case "batch.expiring" -> "Expiring stock alert";
            case "storefront.order.placed" -> "New web order";
            case "storefront.order.paid" -> "Web order paid";
            case "order.received" -> "Order received";
            case "order.payment_received" -> "Payment received";
            case "order.confirmed" -> "Order confirmed";
            case "order.dispatched" -> "Ready for pickup";
            case "order.delivered" -> "Order complete";
            case "approval.requested" -> "Approval requested";
            case "approval.resolved" -> "Approval resolved";
            case "export.completed" -> "Export ready";
            case "credit_sale.reminder" -> "Credit purchase";
            case "sales.daily_digest" -> "Daily sales summary";
            default -> type;
        };
    }

    private static String stringOr(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? fallback : s;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to serialize payload", e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Event records — Slice 1
    // ═══════════════════════════════════════════════════════════════

    public record NotificationCreatedEvent(Notification notification) {}
    public record NotificationReadEvent(String businessId, String userId, String notificationId) {}

    // ═══════════════════════════════════════════════════════════════
    // Event records — Slice 2 (POS-critical, ephemeral)
    // ═══════════════════════════════════════════════════════════════

    public record StockDepletedEvent(
            String businessId, String branchId, String itemId, String itemName, String batchId) {}

    public record PriceChangedEvent(
            String businessId, String branchId, String itemId, String itemName,
            BigDecimal oldPrice, BigDecimal newPrice) {}

    public record PaymentConfirmedEvent(
            String businessId, String branchId, String saleId, BigDecimal amount,
            String paymentMethod, String cashierUserId) {}

    public record StkPaymentSettledEvent(
            String businessId,
            String checkoutRequestId,
            String merchantReference,
            String contextType,
            String contextId,
            boolean success,
            String message) {}

    public record ApprovalRequestedEvent(
            String businessId, String branchId, String approvalId, String adjustmentType,
            String requestedBy, String itemId, String itemName,
            BigDecimal quantity, String reason) {}

    public record ApprovalResolvedEvent(
            String businessId, String branchId, String approvalId, String status,
            String resolvedBy, String requestedByUserId) {}

    // ═══════════════════════════════════════════════════════════════
    // Event records — Phase 2 (transfers, shifts, stock)
    // ═══════════════════════════════════════════════════════════════

    public record TransferInitiatedEvent(
            String businessId, String fromBranchId, String toBranchId,
            String transferId, int itemCount) {}

    /** Phase 9: Fired when a transfer is sent (draft → in_transit). */
    public record TransferSentEvent(
            String businessId, String fromBranchId, String toBranchId,
            String transferId, int itemCount) {}

    public record TransferReceivedEvent(
            String businessId, String fromBranchId, String toBranchId,
            String transferId) {}

    /** Phase 9: Fired when an in-transit transfer is cancelled. */
    public record TransferCancelledEvent(
            String businessId, String fromBranchId, String toBranchId,
            String transferId) {}

    public record ShiftOpenedEvent(
            String businessId, String branchId, String shiftId,
            String openedBy, java.math.BigDecimal openingCash) {}

    public record ShiftClosedEvent(
            String businessId, String branchId, String shiftId,
            String closedBy, java.math.BigDecimal expectedCash,
            java.math.BigDecimal countedCash, java.math.BigDecimal variance) {}

    public record StockAdjustedEvent(
            String businessId, String branchId, String itemId, String itemName,
            String adjustmentType, java.math.BigDecimal quantityDelta) {}

    public record StockLowEvent(
            String businessId, String branchId, String itemId, String itemName,
            java.math.BigDecimal currentStock, java.math.BigDecimal reorderLevel) {}

    public record SystemAnnouncementEvent(
            String businessId, String title, String body, String level) {}
    // ═══════════════════════════════════════════════════════════════
    // Grocery Checkout Events
    // ═══════════════════════════════════════════════════════════════

    public record GroceryInvoiceCreatedEvent(
            String businessId, String branchId, String invoiceId,
            String barcodeCode, java.math.BigDecimal grandTotal,
            int lineCount, String createdBy, String createdByName) {}

    public record GroceryInvoiceLockedEvent(
            String businessId, String branchId, String invoiceId,
            String barcodeCode, String lockedBy, String lockedByName) {}

    public record GroceryInvoiceUnlockedEvent(
            String businessId, String branchId, String invoiceId,
            String barcodeCode) {}

    public record GroceryInvoicePaidEvent(
            String businessId, String branchId, String invoiceId,
            String barcodeCode, String saleId, String paidBy,
            String paidByName) {}

    public record GroceryInvoiceCancelledEvent(
            String businessId, String branchId, String invoiceId,
            String barcodeCode) {}

    public record GroceryInvoiceExpiredEvent(
            String businessId, String branchId, String invoiceId,
            String barcodeCode) {}

}
