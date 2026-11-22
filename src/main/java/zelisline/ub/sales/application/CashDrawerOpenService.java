package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.CashDrawerOpenLogResponse;
import zelisline.ub.sales.api.dto.CashDrawerOpenLogRow;
import zelisline.ub.sales.api.dto.CashDrawerOpenLogTender;
import zelisline.ub.sales.api.dto.CashDrawerOpenResponse;
import zelisline.ub.sales.api.dto.PatchCashDrawerOpenRequest;
import zelisline.ub.sales.api.dto.PostCashDrawerOpenRequest;
import zelisline.ub.sales.domain.CashDrawerOpen;
import zelisline.ub.sales.domain.CashDrawerOpenReason;
import zelisline.ub.sales.domain.CashDrawerOpenSource;
import zelisline.ub.sales.domain.CashDrawout;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.CashDrawerOpenRepository;
import zelisline.ub.sales.repository.CashDrawoutRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Saves each cash-drawer opening the till reports. A repeat of the same
 * {@code clientEventId} returns the row already stored.
 */
@Service
@RequiredArgsConstructor
public class CashDrawerOpenService {

    static final Duration FUTURE_SKEW = Duration.ofMinutes(2);
    static final Duration MAX_AGE = Duration.ofDays(7);
    static final int MAX_LIST = 100;
    static final int LOG_MAX = 500;
    static final int TILL_KEY_MAX = 64;

    private final CashDrawerOpenRepository opens;
    private final ShiftRepository shifts;
    private final SaleRepository sales;
    private final SalePaymentRepository salePayments;
    private final CashDrawoutRepository cashDrawouts;
    private final BranchRepository branches;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;
    private final Clock clock;

    @Transactional
    public RecordedCashDrawerOpen record(
            String businessId,
            String userId,
            String tillDeviceKey,
            PostCashDrawerOpenRequest body) {
        String clientEventId = requireUuid(body.clientEventId(), "clientEventId");
        CashDrawerOpen existing = opens
                .findByBusinessIdAndClientEventId(businessId, clientEventId)
                .orElse(null);
        if (existing != null) {
            return new RecordedCashDrawerOpen(toResponse(existing), false);
        }
        CashDrawerOpen row = newOpening(businessId, userId, tillDeviceKey, clientEventId, body);
        opens.save(row);
        publishOpened(row, userId);
        return new RecordedCashDrawerOpen(toResponse(row), true);
    }

    @Transactional
    public CashDrawerOpenResponse close(
            String businessId,
            String clientEventId,
            PatchCashDrawerOpenRequest body) {
        if (body.closedAt() == null && body.sensorConfirmed() == null) {
            throw badRequest("Nothing to update.");
        }
        CashDrawerOpen row = requireOwned(businessId, clientEventId);
        if (Boolean.TRUE.equals(body.sensorConfirmed())) {
            row.setSensorConfirmed(true);
        }
        if (body.closedAt() != null && row.getClosedAt() == null) {
            requireClosedAt(row.getOpenedAt(), body.closedAt());
            row.setClosedAt(body.closedAt());
        }
        return toResponse(opens.save(row));
    }

    @Transactional(readOnly = true)
    public CashDrawerOpenResponse find(String businessId, String clientEventId) {
        return toResponse(requireOwned(businessId, clientEventId));
    }

    @Transactional(readOnly = true)
    public List<CashDrawerOpenResponse> listForShift(String businessId, String shiftId) {
        String id = requireUuid(shiftId, "shiftId");
        if (shifts.findByIdAndBusinessId(id, businessId).isEmpty()) {
            throw notFound("Shift not found.");
        }
        return opens
                .findByBusinessIdAndShiftIdOrderByOpenedAtDesc(businessId, id, PageRequest.of(0, MAX_LIST))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CashDrawerOpenLogResponse log(String businessId, String shiftId) {
        String id = requireUuid(shiftId, "shiftId");
        if (shifts.findByIdAndBusinessId(id, businessId).isEmpty()) {
            throw notFound("Shift not found.");
        }
        List<CashDrawerOpen> openings = recentOpenings(businessId, id);
        List<CashDrawerOpenMatcher.CashSale> sales = cashSales(businessId, id);
        List<CashDrawerOpenMatcher.Drawout> drawouts = openDrawouts(id);
        Map<String, CashDrawerOpenMatcher.Hit> hits = CashDrawerOpenMatcher.match(
                toMatchOpenings(explainableOpenings(openings)),
                sales,
                drawouts);
        return toLog(openings, hits, sales, drawouts);
    }

    private CashDrawerOpen newOpening(
            String businessId,
            String userId,
            String tillDeviceKey,
            String clientEventId,
            PostCashDrawerOpenRequest body) {
        requireSourceReason(body.source(), body.reason());
        Instant openedAt = requireOpenedAt(body.openedAt());
        String branchId = requireBranch(businessId, body.branchId());
        String shiftId = requireShift(businessId, branchId, body.shiftId());
        String saleId = requireSale(businessId, branchId, body.saleId());
        CashDrawerOpen row = new CashDrawerOpen();
        row.setId(UUID.randomUUID().toString());
        row.setBusinessId(businessId);
        row.setBranchId(branchId);
        row.setShiftId(shiftId);
        row.setSaleId(saleId);
        row.setClientEventId(clientEventId);
        row.setOpenedAt(openedAt);
        row.setSource(body.source());
        row.setReason(body.reason());
        row.setSensorConfirmed(body.source() == CashDrawerOpenSource.KEY
                || Boolean.TRUE.equals(body.sensorConfirmed()));
        row.setPerformedBy(userId);
        row.setTillDeviceKey(tillKey(tillDeviceKey));
        row.setCreatedAt(clock.instant());
        return row;
    }

    private CashDrawerOpen requireOwned(String businessId, String clientEventId) {
        String id = requireUuid(clientEventId, "clientEventId");
        return opens.findByBusinessIdAndClientEventId(businessId, id)
                .orElseThrow(() -> notFound("Drawer opening not found."));
    }

    private void requireSourceReason(CashDrawerOpenSource source, CashDrawerOpenReason reason) {
        if (source == CashDrawerOpenSource.KEY && reason != CashDrawerOpenReason.KEY) {
            throw badRequest("A key opening is recorded only as a key.");
        }
        if (source == CashDrawerOpenSource.TRIGGERED && reason == CashDrawerOpenReason.KEY) {
            throw badRequest("A pulse from the till is not a key opening.");
        }
    }

    private Instant requireOpenedAt(Instant openedAt) {
        Instant now = clock.instant();
        if (openedAt.isAfter(now.plus(FUTURE_SKEW))) {
            throw badRequest("That opening time is ahead of the server clock.");
        }
        if (openedAt.isBefore(now.minus(MAX_AGE))) {
            throw badRequest("That opening time is too old to record.");
        }
        return openedAt;
    }

    private void requireClosedAt(Instant openedAt, Instant closedAt) {
        if (closedAt.isBefore(openedAt)) {
            throw badRequest("The drawer cannot close before it opened.");
        }
        if (closedAt.isAfter(clock.instant().plus(FUTURE_SKEW))) {
            throw badRequest("That closing time is ahead of the server clock.");
        }
    }

    private String requireBranch(String businessId, String branchId) {
        String id = branchId.trim();
        if (branches.findByIdAndBusinessIdAndDeletedAtIsNull(id, businessId).isEmpty()) {
            throw notFound("Branch not found.");
        }
        return id;
    }

    private String requireShift(String businessId, String branchId, String shiftId) {
        String id = blankToNull(shiftId);
        if (id == null) {
            return null;
        }
        Shift shift = shifts.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> notFound("Shift not found."));
        if (!branchId.equals(shift.getBranchId())) {
            throw badRequest("That shift is on another branch.");
        }
        return shift.getId();
    }

    private String requireSale(String businessId, String branchId, String saleId) {
        String id = blankToNull(saleId);
        if (id == null) {
            return null;
        }
        Sale sale = sales.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> notFound("Sale not found."));
        if (!branchId.equals(sale.getBranchId())) {
            throw badRequest("That sale is on another branch.");
        }
        return sale.getId();
    }

    private List<CashDrawerOpen> recentOpenings(String businessId, String shiftId) {
        List<CashDrawerOpen> rows = opens.findByBusinessIdAndShiftIdOrderByOpenedAtAsc(businessId, shiftId);
        if (rows.size() <= LOG_MAX) {
            return rows;
        }
        return rows.subList(rows.size() - LOG_MAX, rows.size());
    }

    /** A till pulse with no reason is the drawer opened on its own. A nearby sale must not claim it. */
    private static List<CashDrawerOpen> explainableOpenings(List<CashDrawerOpen> openings) {
        List<CashDrawerOpen> out = new ArrayList<>();
        for (CashDrawerOpen row : openings) {
            if (row.getReason() != CashDrawerOpenReason.UNKNOWN) {
                out.add(row);
            }
        }
        return out;
    }

    private List<CashDrawerOpenMatcher.Opening> toMatchOpenings(List<CashDrawerOpen> openings) {
        List<CashDrawerOpenMatcher.Opening> out = new ArrayList<>();
        for (CashDrawerOpen row : openings) {
            out.add(new CashDrawerOpenMatcher.Opening(row.getId(), row.getOpenedAt(), row.getSaleId()));
        }
        return out;
    }

    private List<CashDrawerOpenMatcher.CashSale> cashSales(String businessId, String shiftId) {
        List<Sale> sales = this.sales.findByBusinessIdAndShiftId(businessId, shiftId);
        if (sales.isEmpty()) {
            return List.of();
        }
        Map<String, Sale> byId = new HashMap<>();
        for (Sale sale : sales) {
            if (sale.getId() != null) {
                byId.put(sale.getId(), sale);
            }
        }
        if (byId.isEmpty()) {
            return List.of();
        }
        return cashSalesFrom(byId, salePayments.findBySaleIdInAndMethod(
                byId.keySet(), SalesConstants.PAYMENT_METHOD_CASH));
    }

    private static List<CashDrawerOpenMatcher.CashSale> cashSalesFrom(
            Map<String, Sale> byId,
            List<SalePayment> payments) {
        Map<String, BigDecimal> totals = new HashMap<>();
        for (SalePayment payment : payments) {
            BigDecimal amount = payment.getAmount();
            if (payment.getSaleId() == null || amount == null || amount.signum() <= 0) {
                continue;
            }
            totals.merge(payment.getSaleId(), amount, BigDecimal::add);
        }
        List<CashDrawerOpenMatcher.CashSale> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : totals.entrySet()) {
            Sale sale = byId.get(entry.getKey());
            if (sale == null) {
                continue;
            }
            out.add(new CashDrawerOpenMatcher.CashSale(
                    sale.getId(),
                    receiptLabel(sale.getReceiptNo()),
                    sale.getSoldAt(),
                    entry.getValue()));
        }
        return out;
    }

    private List<CashDrawerOpenMatcher.Drawout> openDrawouts(String shiftId) {
        List<CashDrawerOpenMatcher.Drawout> out = new ArrayList<>();
        for (CashDrawout row : cashDrawouts.findByShiftIdOrderByCreatedAtDesc(shiftId)) {
            if (!countsAsDrawerOpen(row.getStatus()) || row.getCreatedAt() == null) {
                continue;
            }
            if (row.getAmount() == null || row.getAmount().signum() <= 0 || row.getId() == null) {
                continue;
            }
            out.add(new CashDrawerOpenMatcher.Drawout(
                    row.getId(), row.getCreatedAt(), row.getAmount(), drawoutLabel(row)));
        }
        return out;
    }

    private static CashDrawerOpenLogResponse toLog(
            List<CashDrawerOpen> openings,
            Map<String, CashDrawerOpenMatcher.Hit> hits,
            List<CashDrawerOpenMatcher.CashSale> sales,
            List<CashDrawerOpenMatcher.Drawout> drawouts) {
        List<CashDrawerOpenLogRow> rows = new ArrayList<>();
        int unmatched = 0;
        for (CashDrawerOpen row : openings) {
            CashDrawerOpenMatcher.Hit hit = hits.get(row.getId());
            if (hit == null) {
                unmatched++;
            }
            rows.add(toLogRow(row, hit));
        }
        return new CashDrawerOpenLogResponse(rows, unmatched, tenders(openings, hits, sales, drawouts));
    }

    private static List<CashDrawerOpenLogTender> tenders(
            List<CashDrawerOpen> openings,
            Map<String, CashDrawerOpenMatcher.Hit> hits,
            List<CashDrawerOpenMatcher.CashSale> sales,
            List<CashDrawerOpenMatcher.Drawout> drawouts) {
        Map<String, Instant> openedByRef = openedByRef(openings, hits);
        List<CashDrawerOpenLogTender> out = new ArrayList<>();
        for (CashDrawerOpenMatcher.CashSale sale : sales) {
            out.add(tender(
                    sale.saleId(),
                    CashDrawerOpenMatcher.KIND_CASH,
                    sale.label(),
                    sale.cashAmount(),
                    sale.soldAt(),
                    openedByRef));
        }
        for (CashDrawerOpenMatcher.Drawout drawout : drawouts) {
            out.add(tender(
                    drawout.id(),
                    CashDrawerOpenMatcher.KIND_DRAWOUT,
                    drawout.label(),
                    drawout.amount(),
                    drawout.at(),
                    openedByRef));
        }
        out.sort(Comparator.comparing(
                CashDrawerOpenLogTender::at,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    private static CashDrawerOpenLogTender tender(
            String id,
            String kind,
            String label,
            BigDecimal amount,
            Instant at,
            Map<String, Instant> openedByRef) {
        Instant opened = openedByRef.get(kind + ":" + id);
        return new CashDrawerOpenLogTender(id, kind, label, amount, at, opened != null, opened);
    }

    private static Map<String, Instant> openedByRef(
            List<CashDrawerOpen> openings,
            Map<String, CashDrawerOpenMatcher.Hit> hits) {
        Map<String, Instant> byRef = new HashMap<>();
        for (CashDrawerOpen row : openings) {
            CashDrawerOpenMatcher.Hit hit = hits.get(row.getId());
            if (hit == null || hit.refId() == null) {
                continue;
            }
            byRef.put(hit.kind() + ":" + hit.refId(), row.getOpenedAt());
        }
        return byRef;
    }

    private static CashDrawerOpenLogRow toLogRow(CashDrawerOpen row, CashDrawerOpenMatcher.Hit hit) {
        if (hit == null) {
            return new CashDrawerOpenLogRow(
                    row.getId(), row.getOpenedAt(), row.getClosedAt(),
                    row.getSource(), row.getReason(), false,
                    null, null, null, null);
        }
        return new CashDrawerOpenLogRow(
                row.getId(), row.getOpenedAt(), row.getClosedAt(),
                row.getSource(), row.getReason(), true,
                hit.kind(), hit.label(), hit.amount(), hit.at());
    }

    private static boolean countsAsDrawerOpen(String status) {
        return SalesConstants.DRAWOUT_STATUS_APPROVED.equals(status)
                || SalesConstants.DRAWOUT_STATUS_PENDING_APPROVAL.equals(status);
    }

    private static String receiptLabel(Long receiptNo) {
        if (receiptNo == null) {
            return "Cash sale";
        }
        return "Receipt " + receiptNo;
    }

    private static String drawoutLabel(CashDrawout row) {
        String who = text(row.getRecipientName());
        if (who != null) {
            return who;
        }
        String description = text(row.getDescription());
        if (description != null) {
            return description;
        }
        String category = text(row.getCategory());
        if (category != null) {
            return category.replace('_', ' ');
        }
        return "Drawout";
    }

    private static String text(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private void publishOpened(CashDrawerOpen row, String userId) {
        auditEventPublisher.publish(auditEventBuilder
                .builder(AuditEventCategory.CASH_DRAWER, AuditEventTypes.CASH_DRAWER_OPENED, AuditEventSeverity.INFO)
                .businessId(row.getBusinessId())
                .branchId(row.getBranchId())
                .actor(userId, AuditEventActorType.USER)
                .target("cash_drawer_open", row.getId())
                .targetLabel(row.getSource() == CashDrawerOpenSource.KEY ? "Key" : "Till pulse")
                .shiftId(row.getShiftId())
                .source("pos_terminal")
                .terminalId(row.getTillDeviceKey())
                .newState(openState(row))
                .createdAt(row.getOpenedAt())
                .build());
    }

    private static Map<String, String> openState(CashDrawerOpen row) {
        Map<String, String> state = new LinkedHashMap<>();
        state.put("source", row.getSource().name());
        state.put("reason", row.getReason().name());
        state.put("sensorConfirmed", Boolean.toString(row.isSensorConfirmed()));
        if (row.getSaleId() != null) {
            state.put("saleId", row.getSaleId());
        }
        return state;
    }

    private CashDrawerOpenResponse toResponse(CashDrawerOpen row) {
        return new CashDrawerOpenResponse(
                row.getId(),
                row.getClientEventId(),
                row.getBranchId(),
                row.getShiftId(),
                row.getSaleId(),
                row.getOpenedAt(),
                row.getClosedAt(),
                row.getSource(),
                row.getReason(),
                row.isSensorConfirmed(),
                row.getPerformedBy(),
                row.getTillDeviceKey());
    }

    private static String requireUuid(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw badRequest(field + " must be a UUID.");
        }
        try {
            return UUID.fromString(raw.trim()).toString();
        } catch (IllegalArgumentException ex) {
            throw badRequest(field + " must be a UUID.");
        }
    }

    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private static String tillKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() <= TILL_KEY_MAX) {
            return trimmed;
        }
        return trimmed.substring(0, TILL_KEY_MAX);
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
