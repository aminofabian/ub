package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
import zelisline.ub.credits.application.CreditSaleDebtService;
import zelisline.ub.finance.application.LedgerAccountResolver;
import zelisline.ub.finance.application.LedgerPostingPort;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.sales.SalePaymentLedger;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.AdjustSalePaymentsRequest;
import zelisline.ub.sales.api.dto.PostSalePaymentRequest;
import zelisline.ub.sales.api.dto.SaleResponse;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;

/**
 * Admin correction when a cashier mis-records tender (e.g. M-Pesa as cash/credit).
 * Replaces {@code sale_payments}, reclasses GL tender accounts, syncs credit debt,
 * and adjusts open-shift drawer expected cash when the cash portion changes.
 */
@Service
@RequiredArgsConstructor
public class SalePaymentAdjustService {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final int MONEY_SCALE = 2;

    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final SalePaymentRepository salePaymentRepository;
    private final ShiftRepository shiftRepository;
    private final LedgerPostingPort ledgerPostingPort;
    private final LedgerAccountResolver ledgerAccountResolver;
    private final CreditSaleDebtService creditSaleDebtService;
    private final SaleActorNameService saleActorNameService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional
    public SaleResponse adjustPayments(
            String businessId,
            String saleId,
            AdjustSalePaymentsRequest req,
            String userId
    ) {
        Sale sale = saleRepository.findByIdAndBusinessId(saleId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale not found"));
        assertSaleAdjustable(sale);

        List<SalePayment> oldPayments = salePaymentRepository.findBySaleIdOrderBySortOrderAsc(saleId);
        List<NormalizedPayment> newPayments = normalizePayments(req.payments(), sale.getGrandTotal());
        assertAdjustableMethods(oldPayments, newPayments);
        assertCreditCustomer(sale, newPayments);

        if (sameTender(oldPayments, newPayments)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment methods are unchanged");
        }

        BigDecimal oldCash = sumCash(oldPayments);
        BigDecimal newCash = sumCashNormalized(newPayments);
        BigDecimal cashDelta = newCash.subtract(oldCash).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        postReclassJournal(businessId, saleId, oldPayments, newPayments);
        applyCreditDebtDelta(businessId, sale, oldPayments, newPayments);
        replacePayments(saleId, newPayments);
        clearCashReceivedIfNeeded(sale, newPayments);
        saleRepository.save(sale);

        if (cashDelta.signum() != 0) {
            applyOpenShiftCashDelta(businessId, sale, cashDelta);
        }

        publishAudit(businessId, sale, userId, oldPayments, newPayments, cashDelta, blankToNull(req.reason()));
        return responseFor(sale);
    }

    private void assertSaleAdjustable(Sale sale) {
        if (SalesConstants.SALE_STATUS_VOIDED.equals(sale.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot adjust payments on a voided sale");
        }
        if (!SalesConstants.SALE_STATUS_COMPLETED.equals(sale.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only completed sales can have payments adjusted");
        }
        if (sale.getRefundedTotal() != null && sale.getRefundedTotal().signum() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot adjust payments on a sale that has refunds");
        }
    }

    private List<NormalizedPayment> normalizePayments(List<PostSalePaymentRequest> payments, BigDecimal grandTotal) {
        if (payments == null || payments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one payment is required");
        }
        List<NormalizedPayment> out = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal gp = grandTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        for (PostSalePaymentRequest p : payments) {
            String m = normalizeMethod(p.method());
            BigDecimal amt = p.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            if (amt.signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment amount must be positive");
            }
            String ref = blankToNull(p.reference());
            forbidReferenceOnNonReferenceTender(m, ref);
            sum = sum.add(amt);
            out.add(new NormalizedPayment(m, amt, ref));
        }
        sum = sum.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (sum.subtract(gp).abs().compareTo(TOLERANCE) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Adjusted payments must equal the sale total (" + gp.toPlainString() + ")");
        }
        return out;
    }

    private static void assertAdjustableMethods(List<SalePayment> oldPayments, List<NormalizedPayment> newPayments) {
        for (SalePayment p : oldPayments) {
            if (isRestrictedMethod(p.getMethod())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This sale uses wallet or loyalty tender; void and re-sell instead of adjusting");
            }
        }
        for (NormalizedPayment p : newPayments) {
            if (isRestrictedMethod(p.method())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Cannot adjust to wallet or loyalty tender; void and re-sell instead");
            }
        }
    }

    private static boolean isRestrictedMethod(String method) {
        return SalesConstants.PAYMENT_METHOD_CUSTOMER_WALLET.equals(method)
                || SalesConstants.PAYMENT_METHOD_LOYALTY_REDEEM.equals(method);
    }

    private void assertCreditCustomer(Sale sale, List<NormalizedPayment> newPayments) {
        BigDecimal credit = sumMethodNormalized(newPayments, SalesConstants.PAYMENT_METHOD_CUSTOMER_CREDIT);
        if (credit.signum() <= 0) {
            return;
        }
        String customerId = sale.getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Sale has no customer; cannot set credit tender. Void and re-sell with a customer.");
        }
        creditSaleDebtService.assertCustomerExists(sale.getBusinessId(), customerId);
    }

    private void applyCreditDebtDelta(
            String businessId,
            Sale sale,
            List<SalePayment> oldPayments,
            List<NormalizedPayment> newPayments
    ) {
        BigDecimal oldCredit = sumMethod(oldPayments, SalesConstants.PAYMENT_METHOD_CUSTOMER_CREDIT);
        BigDecimal newCredit = sumMethodNormalized(newPayments, SalesConstants.PAYMENT_METHOD_CUSTOMER_CREDIT);
        if (oldCredit.compareTo(newCredit) == 0) {
            return;
        }
        if (oldCredit.signum() > 0) {
            creditSaleDebtService.reverseDebtForVoidedSale(businessId, sale, oldPayments);
        }
        if (newCredit.signum() > 0) {
            creditSaleDebtService.applyDebtForNewSale(businessId, sale.getId(), sale.getCustomerId(), newCredit);
        }
    }

    private void replacePayments(String saleId, List<NormalizedPayment> newPayments) {
        salePaymentRepository.deleteBySaleId(saleId);
        salePaymentRepository.flush();
        int order = 0;
        for (NormalizedPayment p : newPayments) {
            SalePayment row = new SalePayment();
            row.setSaleId(saleId);
            row.setMethod(p.method());
            row.setAmount(p.amount());
            row.setReference(p.reference());
            row.setSortOrder(order++);
            salePaymentRepository.save(row);
        }
    }

    private void clearCashReceivedIfNeeded(Sale sale, List<NormalizedPayment> newPayments) {
        boolean singleCash = newPayments.size() == 1
                && SalesConstants.PAYMENT_METHOD_CASH.equals(newPayments.get(0).method());
        if (!singleCash) {
            sale.setCashReceived(null);
        }
    }

    private void applyOpenShiftCashDelta(String businessId, Sale sale, BigDecimal cashDelta) {
        Shift shift = shiftRepository.findByIdAndBusinessIdForUpdate(sale.getShiftId(), businessId)
                .orElse(null);
        if (shift == null || !SalesConstants.SHIFT_STATUS_OPEN.equals(shift.getStatus())) {
            return;
        }
        BigDecimal next = shift.getExpectedClosingCash()
                .add(cashDelta)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (next.signum() < 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Adjustment would make expected drawer cash negative; reconcile the drawer first");
        }
        shift.setExpectedClosingCash(next);
        shiftRepository.save(shift);
    }

    private void postReclassJournal(
            String businessId,
            String saleId,
            List<SalePayment> oldPayments,
            List<NormalizedPayment> newPayments
    ) {
        Map<String, BigDecimal> oldByCode = tenderByLedgerCode(oldPayments);
        Map<String, BigDecimal> newByCode = tenderByLedgerCodeNormalized(newPayments);

        Map<String, BigDecimal> net = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : oldByCode.entrySet()) {
            net.merge(e.getKey(), e.getValue().negate(), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> e : newByCode.entrySet()) {
            net.merge(e.getKey(), e.getValue(), BigDecimal::add);
        }

        boolean any = false;
        for (BigDecimal v : net.values()) {
            if (v.setScale(MONEY_SCALE, RoundingMode.HALF_UP).abs().compareTo(TOLERANCE) > 0) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }

        JournalEntry entry = new JournalEntry();
        entry.setBusinessId(businessId);
        entry.setEntryDate(LocalDate.now(ZoneOffset.UTC));
        entry.setSourceType(SalesConstants.JOURNAL_SOURCE_SALE_PAYMENT_ADJUST);
        entry.setSourceId(saleId);
        entry.setMemo("Payment adjust " + saleId);

        for (Map.Entry<String, BigDecimal> e : net.entrySet()) {
            BigDecimal amt = e.getValue().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            if (amt.abs().compareTo(TOLERANCE) <= 0) {
                continue;
            }
            String accountId = ledgerAccountResolver.resolveId(businessId, e.getKey());
            if (amt.signum() > 0) {
                entry.debit(accountId, amt);
            } else {
                entry.credit(accountId, amt.abs());
            }
        }
        ledgerPostingPort.post(entry);
    }

    private static Map<String, BigDecimal> tenderByLedgerCode(List<SalePayment> payments) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (SalePayment p : payments) {
            String code = SalePaymentLedger.ledgerCodeForPaymentMethod(p.getMethod());
            map.merge(code, p.getAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP), BigDecimal::add);
        }
        return map;
    }

    private static Map<String, BigDecimal> tenderByLedgerCodeNormalized(List<NormalizedPayment> payments) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (NormalizedPayment p : payments) {
            String code = SalePaymentLedger.ledgerCodeForPaymentMethod(p.method());
            map.merge(code, p.amount(), BigDecimal::add);
        }
        return map;
    }

    private void publishAudit(
            String businessId,
            Sale sale,
            String userId,
            List<SalePayment> oldPayments,
            List<NormalizedPayment> newPayments,
            BigDecimal cashDelta,
            String reason
    ) {
        auditEventPublisher.publish(auditEventBuilder.builder(
                        AuditEventCategory.SALES, AuditEventTypes.PAYMENT_ADJUSTED, AuditEventSeverity.INFO)
                .businessId(businessId)
                .branchId(sale.getBranchId())
                .actor(userId, AuditEventActorType.USER)
                .target("sale", sale.getId())
                .targetLabel("Sale " + sale.getId().substring(0, 8))
                .shiftId(sale.getShiftId())
                .oldState(map("paymentMethods", paymentSummary(oldPayments)))
                .newState(map("paymentMethods", paymentSummaryNormalized(newPayments)))
                .source("sales_admin")
                .reason(reason)
                .build());

        if (cashDelta.signum() != 0) {
            auditEventPublisher.publish(auditEventBuilder.builder(
                            AuditEventCategory.CASH_DRAWER, AuditEventTypes.CASH_PAYMENT_ADJUSTED, AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .branchId(sale.getBranchId())
                    .actor(userId, AuditEventActorType.USER)
                    .target("shift", sale.getShiftId())
                    .targetLabel("Shift " + sale.getShiftId().substring(0, 8))
                    .shiftId(sale.getShiftId())
                    .newState(map("cashDelta", cashDelta.toPlainString()))
                    .source("sales_admin")
                    .reason("Payment adjust: " + sale.getId())
                    .build());
        }
    }

    private SaleResponse responseFor(Sale sale) {
        return SaleResponseMapper.map(
                sale,
                saleItemRepository.findBySaleIdOrderByLineIndexAsc(sale.getId()),
                salePaymentRepository.findBySaleIdOrderBySortOrderAsc(sale.getId()),
                saleActorNameService.resolveSoldByName(sale.getBusinessId(), sale.getSoldBy())
        );
    }

    private static boolean sameTender(List<SalePayment> oldPayments, List<NormalizedPayment> newPayments) {
        if (oldPayments.size() != newPayments.size()) {
            return false;
        }
        for (int i = 0; i < oldPayments.size(); i++) {
            SalePayment o = oldPayments.get(i);
            NormalizedPayment n = newPayments.get(i);
            if (!Objects.equals(o.getMethod(), n.method())
                    || o.getAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP).compareTo(n.amount()) != 0
                    || !Objects.equals(blankToNull(o.getReference()), n.reference())) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment method required");
        }
        String m = raw.trim().toLowerCase(Locale.ROOT);
        if ("mpesa".equals(m)) {
            m = SalesConstants.PAYMENT_METHOD_MPESA_MANUAL;
        }
        if (!SalesConstants.PAYMENT_METHOD_CASH.equals(m)
                && !SalesConstants.PAYMENT_METHOD_MPESA_MANUAL.equals(m)
                && !SalesConstants.PAYMENT_METHOD_CARD.equals(m)
                && !SalesConstants.PAYMENT_METHOD_CUSTOMER_CREDIT.equals(m)
                && !SalesConstants.PAYMENT_METHOD_CUSTOMER_WALLET.equals(m)
                && !SalesConstants.PAYMENT_METHOD_LOYALTY_REDEEM.equals(m)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported payment method");
        }
        return m;
    }

    private static void forbidReferenceOnNonReferenceTender(String method, String ref) {
        if (ref == null) {
            return;
        }
        if (SalesConstants.PAYMENT_METHOD_CUSTOMER_CREDIT.equals(method)
                || SalesConstants.PAYMENT_METHOD_CUSTOMER_WALLET.equals(method)
                || SalesConstants.PAYMENT_METHOD_LOYALTY_REDEEM.equals(method)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This tender does not use a payment reference");
        }
    }

    private static BigDecimal sumCash(List<SalePayment> payments) {
        return sumMethod(payments, SalesConstants.PAYMENT_METHOD_CASH);
    }

    private static BigDecimal sumCashNormalized(List<NormalizedPayment> payments) {
        return sumMethodNormalized(payments, SalesConstants.PAYMENT_METHOD_CASH);
    }

    private static BigDecimal sumMethod(List<SalePayment> payments, String method) {
        BigDecimal s = BigDecimal.ZERO;
        for (SalePayment p : payments) {
            if (method.equals(p.getMethod())) {
                s = s.add(p.getAmount());
            }
        }
        return s.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal sumMethodNormalized(List<NormalizedPayment> payments, String method) {
        BigDecimal s = BigDecimal.ZERO;
        for (NormalizedPayment p : payments) {
            if (method.equals(p.method())) {
                s = s.add(p.amount());
            }
        }
        return s.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static Map<String, BigDecimal> paymentSummary(List<SalePayment> payments) {
        Map<String, BigDecimal> summary = new LinkedHashMap<>();
        for (SalePayment p : payments) {
            summary.merge(p.getMethod(), p.getAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP), BigDecimal::add);
        }
        return summary;
    }

    private static Map<String, BigDecimal> paymentSummaryNormalized(List<NormalizedPayment> payments) {
        Map<String, BigDecimal> summary = new LinkedHashMap<>();
        for (NormalizedPayment p : payments) {
            summary.merge(p.method(), p.amount(), BigDecimal::add);
        }
        return summary;
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }

    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private record NormalizedPayment(String method, BigDecimal amount, String reference) {
    }
}
