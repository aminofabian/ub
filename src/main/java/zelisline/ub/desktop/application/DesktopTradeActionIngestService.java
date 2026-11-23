package zelisline.ub.desktop.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.desktop.api.dto.TradeActionSyncSnapshot;
import zelisline.ub.desktop.domain.DesktopTradeAction;
import zelisline.ub.desktop.domain.DesktopTradeActionLogEntry;
import zelisline.ub.desktop.repository.DesktopTradeActionLogRepository;
import zelisline.ub.wholesale.api.dto.AnswerSalesOrderRequest;
import zelisline.ub.wholesale.application.SalesOrderService;
import zelisline.ub.wholesale.application.SalesInvoiceService;
import zelisline.ub.wholesale.application.TradeAccountService;
import zelisline.ub.wholesale.api.dto.DecideTradeAccountRequest;
import zelisline.ub.wholesale.api.dto.RecordSalesReceiptRequest;

/**
 * Cloud-side. Applies one till trade action through the same service the web calls, in one transaction
 * with the idempotency log, so a retried push returns the first outcome and never applies twice.
 * See DESKTOP_SUPPLIER_TRADE_SYNC_SCOPE.md §S2.
 */
@Service
@RequiredArgsConstructor
public class DesktopTradeActionIngestService {

    private static final Logger log = LoggerFactory.getLogger(DesktopTradeActionIngestService.class);

    private final DesktopTradeActionLogRepository logRepository;
    private final SalesOrderService salesOrderService;
    private final SalesInvoiceService salesInvoiceService;
    private final TradeAccountService tradeAccountService;
    private final ObjectMapper objectMapper;

    /**
     * Returns the outcome, or throws for a transient failure the till should retry (nothing is logged then).
     */
    @Transactional
    public TradeActionSyncSnapshot.Outcome applyOne(String businessId, TradeActionSyncSnapshot.Action action) {
        DesktopTradeActionLogEntry existing = logRepository.findById(action.id()).orElse(null);
        if (existing != null) {
            return new TradeActionSyncSnapshot.Outcome(action.id(), existing.getOutcome(), existing.getOutcomeDetail());
        }
        String outcome;
        String detail = null;
        try {
            apply(businessId, action);
            outcome = DesktopTradeAction.OUTCOME_APPLIED;
        } catch (ResponseStatusException e) {
            // Permanent: the cloud cannot apply this and never will on a retry (already answered,
            // cancelled, over limit). Record it refused so the till can show the reason.
            outcome = DesktopTradeAction.OUTCOME_REFUSED;
            detail = e.getReason();
            log.info("[DesktopTrade] refused {} for {}: {}", action.kind(), action.targetId(), detail);
        }
        DesktopTradeActionLogEntry entry = new DesktopTradeActionLogEntry();
        entry.setActionId(action.id());
        entry.setBusinessId(businessId);
        entry.setKind(action.kind());
        entry.setTargetId(action.targetId());
        entry.setOutcome(outcome);
        entry.setOutcomeDetail(detail);
        logRepository.save(entry);
        return new TradeActionSyncSnapshot.Outcome(action.id(), outcome, detail);
    }

    private void apply(String businessId, TradeActionSyncSnapshot.Action action) {
        switch (action.kind()) {
            case DesktopTradeAction.KIND_ANSWER ->
                salesOrderService.answer(businessId, action.targetId(), parseAnswer(action.payload()));
            case DesktopTradeAction.KIND_DISPATCH ->
                salesOrderService.dispatch(businessId, action.targetId());
            case DesktopTradeAction.KIND_CANCEL ->
                salesOrderService.cancel(businessId, action.targetId());
            case DesktopTradeAction.KIND_APPROVE_ACCOUNT ->
                tradeAccountService.decide(businessId, action.targetId(), readDecision(action.payload()));
            case DesktopTradeAction.KIND_RECORD_PAYMENT ->
                salesInvoiceService.recordReceipt(businessId, action.targetId(), readReceipt(action.payload()));
            default ->
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown trade action: " + action.kind());
        }
    }

    private DecideTradeAccountRequest readDecision(String payload) {
        try {
            JsonNode n = objectMapper.readTree(payload == null ? "{}" : payload);
            return new DecideTradeAccountRequest(
                n.path("status").asText("approved"),
                n.hasNonNull("creditLimit") ? new BigDecimal(n.get("creditLimit").asText()) : null,
                n.hasNonNull("termsDays") ? n.get("termsDays").asInt() : null,
                n.hasNonNull("discountPercent") ? new BigDecimal(n.get("discountPercent").asText()) : null);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the account decision from the till");
        }
    }

    private RecordSalesReceiptRequest readReceipt(String payload) {
        try {
            JsonNode n = objectMapper.readTree(payload == null ? "{}" : payload);
            return new RecordSalesReceiptRequest(
                new BigDecimal(n.path("amount").asText("0")),
                n.path("method").asText("cash"),
                n.hasNonNull("reference") ? n.get("reference").asText() : null,
                n.hasNonNull("receivedOn") ? java.time.LocalDate.parse(n.get("receivedOn").asText()) : null);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the payment from the till");
        }
    }

    private AnswerSalesOrderRequest parseAnswer(String payload) {
        List<AnswerSalesOrderRequest.Line> lines = new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(payload == null ? "[]" : payload);
            if (node.isArray()) {
                for (JsonNode l : node) {
                    lines.add(new AnswerSalesOrderRequest.Line(
                        l.path("lineId").asText(""),
                        new BigDecimal(l.path("acceptedQty").asText("0"))));
                }
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the answer from the till");
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The answer had no lines");
        }
        return new AnswerSalesOrderRequest(lines);
    }
}
