package zelisline.ub.desktop.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.desktop.domain.DesktopTradeAction;
import zelisline.ub.desktop.repository.DesktopTradeActionRepository;
import zelisline.ub.wholesale.domain.SalesOrder;
import zelisline.ub.wholesale.domain.SalesOrderLine;
import zelisline.ub.wholesale.domain.SalesOrderStatuses;
import zelisline.ub.wholesale.repository.SalesOrderLineRepository;
import zelisline.ub.wholesale.repository.SalesOrderRepository;
import zelisline.ub.wholesale.domain.TradeAccount;
import zelisline.ub.wholesale.domain.SalesInvoice;
import zelisline.ub.wholesale.domain.SalesReceipt;
import zelisline.ub.wholesale.repository.TradeAccountRepository;
import zelisline.ub.wholesale.repository.SalesInvoiceRepository;
import zelisline.ub.wholesale.repository.SalesReceiptRepository;

/**
 * Desktop till only. Records what a supplier does to a mirrored order and updates the mirror's status
 * optimistically so the UI moves at once -- but never touches stock. The cloud is the sole stock
 * authority; the real hold/dispatch happens when the action is pushed (S2), and the authoritative order
 * comes back on the next pull. See DESKTOP_SUPPLIER_TRADE_SYNC_SCOPE.md.
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopTradeActionService {

    private static final Logger log = LoggerFactory.getLogger(DesktopTradeActionService.class);

    private final DesktopTradeActionRepository actionRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderLineRepository salesOrderLineRepository;
    private final ObjectMapper objectMapper;
    private final TradeAccountRepository tradeAccountRepository;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final SalesReceiptRepository salesReceiptRepository;

    /** Queue an "answer" and set the mirror's status/line quantities to match, so the list and detail move. */
    @Transactional
    public void recordAnswer(String businessId, String orderId, String actorUserId,
                             List<AnswerLine> lines) {
        SalesOrder order = requirePlaced(businessId, orderId);
        List<SalesOrderLine> rows = salesOrderLineRepository
            .findByOrderIdAndBusinessIdOrderByLineIndexAsc(orderId, businessId);
        boolean anyAccepted = false;
        boolean allFull = !rows.isEmpty();
        for (SalesOrderLine row : rows) {
            BigDecimal accepted = lines.stream()
                .filter(l -> l.lineId().equals(row.getId()))
                .map(AnswerLine::acceptedQty)
                .findFirst()
                .orElse(BigDecimal.ZERO);
            row.setAcceptedQty(accepted);
            if (accepted.signum() > 0) {
                anyAccepted = true;
                row.setStatus(SalesOrderStatuses.LINE_ACCEPTED);
            } else {
                row.setStatus(SalesOrderStatuses.LINE_REJECTED);
            }
            if (accepted.compareTo(row.getQuantity()) < 0) {
                allFull = false;
            }
            salesOrderLineRepository.save(row);
        }
        order.setStatus(!anyAccepted
            ? SalesOrderStatuses.REJECTED
            : allFull ? SalesOrderStatuses.ACCEPTED : SalesOrderStatuses.PART_ACCEPTED);
        order.setAnsweredAt(Instant.now());
        salesOrderRepository.save(order);
        queue(businessId, DesktopTradeAction.KIND_ANSWER, orderId, actorUserId, writeAnswerPayload(lines));
    }

    @Transactional
    public void recordDispatch(String businessId, String orderId, String actorUserId) {
        SalesOrder order = require(businessId, orderId);
        if (!SalesOrderStatuses.ACCEPTED.equals(order.getStatus())
                && !SalesOrderStatuses.PART_ACCEPTED.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an accepted order can be dispatched");
        }
        order.setStatus(SalesOrderStatuses.DISPATCHED);
        order.setDispatchedAt(Instant.now());
        salesOrderRepository.save(order);
        queue(businessId, DesktopTradeAction.KIND_DISPATCH, orderId, actorUserId, null);
    }

    @Transactional
    public void recordCancel(String businessId, String orderId, String actorUserId) {
        SalesOrder order = require(businessId, orderId);
        order.setStatus(SalesOrderStatuses.CANCELLED);
        salesOrderRepository.save(order);
        queue(businessId, DesktopTradeAction.KIND_CANCEL, orderId, actorUserId, null);
    }

    /** Queue an account decision and update the mirror so the panel reflects it at once. Payload: the DecideTradeAccountRequest. */
    @Transactional
    public void recordApproveAccount(String businessId, String accountId, String actorUserId, String payloadJson) {
        TradeAccount account = tradeAccountRepository.findByIdAndBusinessId(accountId, businessId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found on this till"));
        try {
            JsonNode node = objectMapper.readTree(payloadJson == null ? "{}" : payloadJson);
            if (!node.path("status").asText("").isBlank()) {
                account.setStatus(node.path("status").asText());
            }
            if (node.hasNonNull("creditLimit")) {
                account.setCreditLimit(new BigDecimal(node.get("creditLimit").asText()));
            }
            if (node.hasNonNull("termsDays")) {
                account.setTermsDays(node.get("termsDays").asInt());
            }
            if (node.hasNonNull("discountPercent")) {
                account.setDiscountPercent(new BigDecimal(node.get("discountPercent").asText()));
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the account decision");
        }
        tradeAccountRepository.save(account);
        queue(businessId, DesktopTradeAction.KIND_APPROVE_ACCOUNT, accountId, actorUserId, payloadJson);
    }

    /** Queue a payment and update the mirror invoice (amount paid, a local receipt row) so it reflects at once. */
    @Transactional
    public void recordPayment(String businessId, String invoiceId, String actorUserId, String payloadJson) {
        SalesInvoice invoice = salesInvoiceRepository.findByIdAndBusinessId(invoiceId, businessId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found on this till"));
        BigDecimal amount;
        try {
            JsonNode node = objectMapper.readTree(payloadJson == null ? "{}" : payloadJson);
            amount = new BigDecimal(node.path("amount").asText("0"));
            if (amount.signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an amount to record");
            }
            SalesReceipt receipt = new SalesReceipt();
            receipt.setId(UUID.randomUUID().toString());
            receipt.setBusinessId(businessId);
            receipt.setInvoiceId(invoiceId);
            receipt.setAmount(amount);
            receipt.setMethod(node.path("method").asText("cash"));
            receipt.setReference(node.path("reference").asText(null));
            salesReceiptRepository.save(receipt);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the payment");
        }
        BigDecimal paid = invoice.getAmountPaid() == null ? BigDecimal.ZERO : invoice.getAmountPaid();
        invoice.setAmountPaid(paid.add(amount));
        salesInvoiceRepository.save(invoice);
        queue(businessId, DesktopTradeAction.KIND_RECORD_PAYMENT, invoiceId, actorUserId, payloadJson);
    }

    private SalesOrder require(String businessId, String orderId) {
        return salesOrderRepository.findByIdAndBusinessId(orderId, businessId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found on this till"));
    }

    private SalesOrder requirePlaced(String businessId, String orderId) {
        SalesOrder order = require(businessId, orderId);
        if (!SalesOrderStatuses.PLACED.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order has already been answered");
        }
        return order;
    }

    private void queue(String businessId, String kind, String targetId, String actorUserId, String payload) {
        DesktopTradeAction action = new DesktopTradeAction();
        action.setId(UUID.randomUUID().toString());
        action.setBusinessId(businessId);
        action.setKind(kind);
        action.setTargetId(targetId);
        action.setActorUserId(actorUserId);
        action.setPayload(payload);
        actionRepository.save(action);
        log.info("[DesktopTrade] queued {} for order {}", kind, targetId);
    }

    private String writeAnswerPayload(List<AnswerLine> lines) {
        try {
            return objectMapper.writeValueAsString(lines);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save the answer");
        }
    }

    /** Read an answer payload back on the cloud side. */
    public List<AnswerLine> readAnswerPayload(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload == null ? "[]" : payload);
            java.util.List<AnswerLine> out = new java.util.ArrayList<>();
            if (node.isArray()) {
                for (JsonNode l : node) {
                    out.add(new AnswerLine(
                        l.path("lineId").asText(""),
                        new BigDecimal(l.path("acceptedQty").asText("0"))));
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    public record AnswerLine(String lineId, BigDecimal acceptedQty) {}
}
