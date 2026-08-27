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
        String eventId = notification.getId() != null && !notification.getId().isBlank()
                ? notification.getId()
                : UUID.randomUUID().toString();
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
        var dataMap = new LinkedHashMap<String, Object>();
        dataMap.put("checkoutRequestId", event.checkoutRequestId() != null ? event.checkoutRequestId() : "");
        dataMap.put("merchantReference", event.merchantReference() != null ? event.merchantReference() : "");
        dataMap.put("contextType", event.contextType() != null ? event.contextType() : "");
        dataMap.put("contextId", event.contextId() != null ? event.contextId() : "");
        dataMap.put("success", event.success());
        dataMap.put("message", event.message() != null ? event.message() : "");
        dataMap.put("gatewayTransactionId",
                event.gatewayTransactionId() != null ? event.gatewayTransactionId() : "");
        if (event.amount() != null) {
            dataMap.put("amount", event.amount().toPlainString());
        }
        String payloadJson = toJson(dataMap);
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

    /**
     * Fan-out Kiosk Pay wallet balances to all sessions for the business (owner header).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onKioskPayBalanceUpdated(KioskPayBalanceUpdatedEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, Object>();
        dataMap.put("businessId", event.businessId() != null ? event.businessId() : "");
        dataMap.put("availableBalance",
                event.availableBalance() != null ? event.availableBalance().toPlainString() : "0");
        dataMap.put("pendingBalance",
                event.pendingBalance() != null ? event.pendingBalance().toPlainString() : "0");
        dataMap.put("currency", event.currency() != null ? event.currency() : "KES");
        dataMap.put("status", event.status() != null ? event.status() : "");
        dataMap.put("reason", event.reason() != null ? event.reason() : "");
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) {
            return;
        }

        Set<String> sessionIds = sessionRegistry.findAllSessionsForBusiness(event.businessId());
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "kiosk_pay.balance.updated", eventId, "MEDIUM", Instant.now(), payloadJson);
        }
        log.debug("Kiosk Pay balance updated: business={} reason={} sessions={}",
                event.businessId(), event.reason(), sessionIds.size());
    }

    /**
     * Airtime is confirmed asynchronously by the telco, so the till watches this
     * instead of holding the cashier on a spinner.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAirtimeOrderUpdated(AirtimeOrderUpdatedEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, Object>();
        dataMap.put("businessId", event.businessId() != null ? event.businessId() : "");
        dataMap.put("orderId", event.orderId() != null ? event.orderId() : "");
        dataMap.put("status", event.status() != null ? event.status() : "");
        dataMap.put("phoneNumber", event.phoneNumber() != null ? event.phoneNumber() : "");
        dataMap.put("amount", event.amount() != null ? event.amount().toPlainString() : "0");
        dataMap.put("commission", event.commission() != null ? event.commission().toPlainString() : "0");
        dataMap.put("currency", event.currency() != null ? event.currency() : "KES");
        dataMap.put("receipt", event.receipt() != null ? event.receipt() : "");
        dataMap.put("failureReason", event.failureReason() != null ? event.failureReason() : "");
        dataMap.put("walletBalance",
                event.walletBalance() != null ? event.walletBalance().toPlainString() : "");
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) {
            return;
        }

        Set<String> sessionIds = sessionRegistry.findAllSessionsForBusiness(event.businessId());
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "airtime.order.updated", eventId, "HIGH", Instant.now(), payloadJson);
        }
        log.debug("Airtime order updated: order={} status={} sessions={}",
                event.orderId(), event.status(), sessionIds.size());
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
     * Fan-out sale.completed to branch POS listeners and business-wide hub sessions
     * (owners/managers with null branch claim). Invalidates Morning board pulse.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSaleCompleted(SaleCompletedEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, String>();
        dataMap.put("saleId", event.saleId());
        dataMap.put("branchId", event.branchId() != null ? event.branchId() : "");
        dataMap.put("amount", event.amount().toPlainString());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchOrBusinessWide(
                event.businessId(), event.branchId(), "pos");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "sale.completed", eventId, "MEDIUM", Instant.now(), payloadJson);
        }
        log.debug("POS event sale.completed: sale={} branch={} sessions={}",
                event.saleId(), event.branchId(), sessionIds.size());
    }

    /**
     * Fan-out supply.posted when a Path B receive or Path A GRN invoice is posted.
     * Invalidates Morning board supply tape + payables pulse.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSupplyPosted(SupplyPostedEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, String>();
        dataMap.put("supplierInvoiceId", event.supplierInvoiceId());
        dataMap.put("invoiceNumber", event.invoiceNumber() != null ? event.invoiceNumber() : "");
        dataMap.put("branchId", event.branchId() != null ? event.branchId() : "");
        dataMap.put("amount", event.amount().toPlainString());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchOrBusinessWide(
                event.businessId(), event.branchId(), "pos");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "supply.posted", eventId, "MEDIUM", Instant.now(), payloadJson);
        }
        log.debug("POS event supply.posted: invoice={} branch={} sessions={}",
                event.supplierInvoiceId(), event.branchId(), sessionIds.size());
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

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchOrBusinessWide(
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

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchOrBusinessWide(
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
        dataMap.put("remote", event.remote() ? "true" : "false");
        if (event.customerPhone() != null) {
            dataMap.put("customerPhone", event.customerPhone());
        }
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
            payload.put("remote", event.remote());
            if (event.customerPhone() != null) {
                payload.put("customerPhone", event.customerPhone());
            }
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGroceryInvoiceStk(GroceryInvoiceStkEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, String>();
        dataMap.put("invoiceId", event.invoiceId());
        dataMap.put("barcodeCode", event.barcodeCode());
        dataMap.put("stkStatus", event.stkStatus() != null ? event.stkStatus() : "");
        if (event.customerPhone() != null) {
            dataMap.put("customerPhone", event.customerPhone());
        }
        if (event.grandTotal() != null) {
            dataMap.put("grandTotal", event.grandTotal().toPlainString());
        }
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;

        Set<String> sessionIds = sessionRegistry.findSessionsByBranchChannel(
                event.businessId(), event.branchId(), "grocery");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "grocery.invoice.stk", eventId, "HIGH", Instant.now(), payloadJson);
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
                 "sales.daily_digest", "inventory.restock_digest", "account.welcome",
                 "onboarding.fill_shelf", "onboarding.sizes_right", "onboarding.money_loop",
                 "onboarding.first_sale", "onboarding.go_live", "onboarding.team_rhythm",
                 "onboarding.week_checkin", "onboarding.reengage", "onboarding.lookalike",
                 "onboarding.close_shift", "onboarding.web_order" -> true;
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
            case "inventory.restock_digest" -> "Tonight's list";
            case "account.welcome" -> "Welcome to Kiosk!";
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

    /** Once-per-sale invalidate signal for dashboards (not cashier payment UX). */
    public record SaleCompletedEvent(
            String businessId, String branchId, String saleId, BigDecimal amount) {}

    /** Once-per-supply-bill signal for dashboards (Path B receive / Path A invoice). */
    public record SupplyPostedEvent(
            String businessId, String branchId, String supplierInvoiceId,
            String invoiceNumber, BigDecimal amount) {}

    public record StkPaymentSettledEvent(
            String businessId,
            String checkoutRequestId,
            String merchantReference,
            String contextType,
            String contextId,
            boolean success,
            String message,
            String gatewayTransactionId,
            BigDecimal amount) {}

    /** Merchant Kiosk Pay wallet balance changed (capture / withdraw). */
    public record KioskPayBalanceUpdatedEvent(
            String businessId,
            BigDecimal availableBalance,
            BigDecimal pendingBalance,
            String currency,
            String status,
            String reason) {}

    /** Airtime order moved on (submitted / delivered / failed). */
    public record AirtimeOrderUpdatedEvent(
            String businessId,
            String orderId,
            String status,
            String phoneNumber,
            BigDecimal amount,
            BigDecimal commission,
            String currency,
            String receipt,
            String failureReason,
            BigDecimal walletBalance) {}

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
            int lineCount, String createdBy, String createdByName,
            boolean remote, String customerPhone) {}

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

    public record GroceryInvoiceStkEvent(
            String businessId,
            String branchId,
            String invoiceId,
            String barcodeCode,
            String stkStatus,
            String customerPhone,
            java.math.BigDecimal grandTotal) {}

    // ═══════════════════════════════════════════════════════════════
    // POS Draft Events (Live Pending Carts)
    // ═══════════════════════════════════════════════════════════════

    public record PosDraftCreatedEvent(
            String businessId, String branchId, String draftId,
            long ticketNumber, String cashierId, String cashierName,
            int lineCount, java.math.BigDecimal grandTotal,
            Instant updatedAt, long version) {}

    public record PosDraftUpdatedEvent(
            String businessId, String branchId, String draftId,
            long ticketNumber, String cashierId, String cashierName,
            int lineCount, java.math.BigDecimal grandTotal,
            Instant updatedAt, long version) {}

    public record PosDraftCancelledEvent(
            String businessId, String branchId, String draftId,
            long ticketNumber) {}

    public record PosDraftCompletedEvent(
            String businessId, String branchId, String draftId,
            long ticketNumber, String saleId) {}

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPosDraftCreated(PosDraftCreatedEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, Object>();
        dataMap.put("draftId", event.draftId());
        dataMap.put("ticketNumber", event.ticketNumber());
        dataMap.put("branchId", event.branchId());
        dataMap.put("cashierId", event.cashierId());
        dataMap.put("cashierName", event.cashierName() != null ? event.cashierName() : "");
        dataMap.put("lineCount", event.lineCount());
        dataMap.put("grandTotal", event.grandTotal().toPlainString());
        dataMap.put("updatedAt", event.updatedAt().toString());
        dataMap.put("version", event.version());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;
        Set<String> sessionIds = sessionRegistry.findSessionsByBranchOrBusinessWide(
                event.businessId(), event.branchId(), "pos_drafts");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "pos_draft.created", eventId, "HIGH", Instant.now(), payloadJson);
        }
        log.debug("POS draft created: draft={} ticket={} branch={} sessions={}",
                event.draftId(), event.ticketNumber(), event.branchId(), sessionIds.size());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPosDraftUpdated(PosDraftUpdatedEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, Object>();
        dataMap.put("draftId", event.draftId());
        dataMap.put("ticketNumber", event.ticketNumber());
        dataMap.put("branchId", event.branchId());
        dataMap.put("cashierId", event.cashierId());
        dataMap.put("cashierName", event.cashierName() != null ? event.cashierName() : "");
        dataMap.put("lineCount", event.lineCount());
        dataMap.put("grandTotal", event.grandTotal().toPlainString());
        dataMap.put("updatedAt", event.updatedAt().toString());
        dataMap.put("version", event.version());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;
        Set<String> sessionIds = sessionRegistry.findSessionsByBranchOrBusinessWide(
                event.businessId(), event.branchId(), "pos_drafts");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "pos_draft.updated", eventId, "MEDIUM", Instant.now(), payloadJson);
        }
        log.debug("POS draft updated: draft={} ticket={} branch={} sessions={}",
                event.draftId(), event.ticketNumber(), event.branchId(), sessionIds.size());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPosDraftCancelled(PosDraftCancelledEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, Object>();
        dataMap.put("draftId", event.draftId());
        dataMap.put("ticketNumber", event.ticketNumber());
        dataMap.put("branchId", event.branchId());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;
        Set<String> sessionIds = sessionRegistry.findSessionsByBranchOrBusinessWide(
                event.businessId(), event.branchId(), "pos_drafts");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "pos_draft.cancelled", eventId, "HIGH", Instant.now(), payloadJson);
        }
        log.debug("POS draft cancelled: draft={} ticket={} branch={} sessions={}",
                event.draftId(), event.ticketNumber(), event.branchId(), sessionIds.size());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPosDraftCompleted(PosDraftCompletedEvent event) {
        String eventId = UUID.randomUUID().toString();
        var dataMap = new LinkedHashMap<String, Object>();
        dataMap.put("draftId", event.draftId());
        dataMap.put("ticketNumber", event.ticketNumber());
        dataMap.put("branchId", event.branchId());
        dataMap.put("saleId", event.saleId());
        String payloadJson = toJson(dataMap);
        if (payloadJson == null) return;
        Set<String> sessionIds = sessionRegistry.findSessionsByBranchOrBusinessWide(
                event.businessId(), event.branchId(), "pos_drafts");
        for (String sid : sessionIds) {
            handler.sendFrame(sid, "pos_draft.completed", eventId, "HIGH", Instant.now(), payloadJson);
        }
        log.debug("POS draft completed: draft={} ticket={} sale={} branch={} sessions={}",
                event.draftId(), event.ticketNumber(), event.saleId(), event.branchId(), sessionIds.size());
    }

}
