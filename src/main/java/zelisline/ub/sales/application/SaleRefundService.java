package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.CreditSaleDebtService;
import zelisline.ub.credits.application.LoyaltyPointsService;
import zelisline.ub.credits.application.WalletLedgerService;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerBootstrapService;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.finance.domain.LedgerAccount;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.JournalLineRepository;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.sales.SalePaymentLedger;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.PostRefundRequest;
import zelisline.ub.sales.api.dto.RefundLineResponse;
import zelisline.ub.sales.api.dto.RefundPaymentResponse;
import zelisline.ub.sales.api.dto.RefundResponse;
import zelisline.ub.sales.domain.Refund;
import zelisline.ub.sales.domain.RefundLine;
import zelisline.ub.sales.domain.RefundPayment;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.RefundLineRepository;
import zelisline.ub.sales.repository.RefundPaymentRepository;
import zelisline.ub.sales.repository.RefundRepository;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;

@Service
@RequiredArgsConstructor
public class SaleRefundService {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final int MONEY_SCALE = 2;
    private static final int QTY_SCALE = 4;

    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final SalePaymentRepository salePaymentRepository;
    private final RefundRepository refundRepository;
    private final RefundLineRepository refundLineRepository;
    private final RefundPaymentRepository refundPaymentRepository;
    private final ShiftRepository shiftRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ItemRepository itemRepository;
    private final LedgerBootstrapService ledgerBootstrapService;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;
    private final CreditSaleDebtService creditSaleDebtService;
    private final WalletLedgerService walletLedgerService;
    private final LoyaltyPointsService loyaltyPointsService;
    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public RefundResponse createRefund(
            String businessId,
            String saleId,
            String rawIdempotencyKey,
            PostRefundRequest req,
            String userId
    ) {
        String idemKey = normalizeIdempotencyKey(rawIdempotencyKey);
        var existingRefund = refundRepository.findByBusinessIdAndIdempotencyKey(businessId, idemKey);
        if (existingRefund.isPresent()) {
            return toRefundResponse(existingRefund.get());
        }

        Sale sale = saleRepository.findByIdAndBusinessIdForUpdate(saleId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale not found"));
        assertSaleRefundable(sale);

        RefundComputation comp = computeRefund(sale, req);
        List<NormalizedPay> pays = normalizeRefundPayments(req.payments(), comp.totalMoney());
        BigDecimal walletRefundShare = sumPayMethod(pays, SalesConstants.PAYMENT_METHOD_CUSTOMER_WALLET);
        assertWalletRefundAllowed(sale, walletRefundShare);
        assertCreditRefundAllowed(sale, pays);

        Shift shift = shiftRepository
                .findByBusinessIdAndBranchIdAndStatusForUpdate(
                        businessId, sale.getBranchId(), SalesConstants.SHIFT_STATUS_OPEN)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No open shift for this branch"));

        String refundId = UUID.randomUUID().toString();
        applyRefundStock(businessId, sale, refundId, comp.rows(), userId);

        applyDrawerRefund(shift, pays);

        String jeId = postRefundJournal(businessId, refundId, comp.totalMoney(), comp.totalCogs(), pays);
        creditSaleDebtService.reduceDebtForCreditRefund(
                businessId, saleId, sale.getCustomerId(), sumCustomerCreditPay(pays));
        walletLedgerService.refundToWallet(businessId, saleId, sale.getCustomerId(), walletRefundShare);
        loyaltyPointsService.proportionallyAdjustAfterRefund(
                businessId, saleId, sale.getCustomerId(), sale.getGrandTotal(), comp.totalMoney());

        Refund refund = persistRefundAggregate(
                businessId,
                saleId,
                idemKey.trim(),
                req.reason().trim(),
                userId,
                comp,
                pays,
                refundId,
                jeId
        );
        finalizeSaleRefundTotals(sale, comp.totalMoney());

        return toRefundResponse(refund);
    }

    private void assertSaleRefundable(Sale sale) {
        if (SalesConstants.SALE_STATUS_VOIDED.equals(sale.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot refund a voided sale");
        }
        if (!SalesConstants.SALE_STATUS_COMPLETED.equals(sale.getStatus())
                && !SalesConstants.SALE_STATUS_REFUNDED.equals(sale.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sale cannot be refunded in its current state");
        }
        BigDecimal refunded = sale.getRefundedTotal() != null ? sale.getRefundedTotal() : BigDecimal.ZERO;
        if (sale.getGrandTotal().subtract(refunded).abs().compareTo(TOLERANCE) <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sale is already fully refunded");
        }
    }

    private RefundComputation computeRefund(Sale sale, PostRefundRequest req) {
        Set<String> seen = new HashSet<>();
        List<RefundRow> rows = new ArrayList<>();
        BigDecimal totalMoney = BigDecimal.ZERO;
        BigDecimal totalCogs = BigDecimal.ZERO;

        BigDecimal refunded = sale.getRefundedTotal() != null ? sale.getRefundedTotal() : BigDecimal.ZERO;
        BigDecimal cap = sale.getGrandTotal().subtract(refunded).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (cap.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No remaining value to refund on this sale");
        }

        for (PostRefundRequest.PostRefundLineRequest line : req.lines()) {
            String sid = line.saleItemId().trim();
            if (!seen.add(sid)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate sale line in refund request");
            }
            SaleItem si = saleItemRepository.findByIdAndSaleId(sid, sale.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown sale line"));
            BigDecimal q = line.quantity().setScale(QTY_SCALE, RoundingMode.HALF_UP);
            if (q.signum() <= 0 || q.compareTo(si.getQuantity()) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid refund quantity for line");
            }
            BigDecimal already = refundLineRepository.sumRefundedQuantityForSaleItem(sid, sale.getId())
                    .setScale(QTY_SCALE, RoundingMode.HALF_UP);
            if (already.add(q).compareTo(si.getQuantity()) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund quantity exceeds remaining on line");
            }
            BigDecimal money = si.getLineTotal()
                    .multiply(q)
                    .divide(si.getQuantity(), MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal cogs = si.getCostTotal()
                    .multiply(q)
                    .divide(si.getQuantity(), MONEY_SCALE, RoundingMode.HALF_UP);
            totalMoney = totalMoney.add(money);
            totalCogs = totalCogs.add(cogs);
            rows.add(new RefundRow(si, q, money, cogs));
        }

        totalMoney = totalMoney.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        totalCogs = totalCogs.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (totalMoney.compareTo(cap) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund total exceeds remaining sale total");
        }
        return new RefundComputation(rows, totalMoney, totalCogs);
    }

    private void applyDrawerRefund(Shift shift, List<NormalizedPay> pays) {
        BigDecimal cashOut = sumCash(pays);
        if (cashOut.signum() <= 0) {
            shiftRepository.save(shift);
            return;
        }
        BigDecimal next = shift.getExpectedClosingCash()
                .subtract(cashOut)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (next.signum() < 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Refund cash would make expected drawer cash negative; reconcile the drawer first"
            );
        }
        shift.setExpectedClosingCash(next);
        shiftRepository.save(shift);
    }

    private void applyRefundStock(
            String businessId,
            Sale sale,
            String refundId,
            List<RefundRow> rows,
            String userId
    ) {
        List<RefundRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(r -> r.saleItem().getItemId()));
        Item item = null;
        String lastItemId = null;
        for (RefundRow row : sorted) {
            SaleItem si = row.saleItem();
            if (!si.getItemId().equals(lastItemId)) {
                if (item != null) {
                    itemRepository.save(item);
                }
                item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(si.getItemId(), businessId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
                entityManager.lock(item, LockModeType.PESSIMISTIC_WRITE);
                lastItemId = si.getItemId();
            }
            BigDecimal q = row.quantity();
            StockTarget target = resolveReturnBatch(businessId, sale, refundId, si, q);
            target.batch().setQuantityRemaining(
                    target.batch().getQuantityRemaining().add(q).setScale(QTY_SCALE, RoundingMode.HALF_UP));
            inventoryBatchRepository.save(target.batch());

            StockMovement sm = new StockMovement();
            sm.setBusinessId(businessId);
            sm.setBranchId(sale.getBranchId());
            sm.setItemId(si.getItemId());
            sm.setBatchId(target.batch().getId());
            sm.setMovementType(InventoryConstants.MOVEMENT_REFUND);
            sm.setReferenceType(SalesConstants.STOCK_REFERENCE_TYPE_SALE_REFUND);
            sm.setReferenceId(refundId);
            sm.setQuantityDelta(q);
            sm.setUnitCost(si.getUnitCost());
            sm.setNotes("Sale refund");
            sm.setCreatedBy(userId);
            stockMovementRepository.save(sm);
            applyItemStock(item, q);
        }
        if (item != null) {
            itemRepository.save(item);
        }
    }

    private StockTarget resolveReturnBatch(
            String businessId,
            Sale sale,
            String refundId,
            SaleItem si,
            BigDecimal quantity
    ) {
        InventoryBatch orig = inventoryBatchRepository
                .findByIdAndBusinessIdForUpdate(si.getBatchId(), businessId)
                .orElse(null);
        if (orig != null
                && orig.getBranchId().equals(sale.getBranchId())
                && InventoryConstants.BATCH_STATUS_ACTIVE.equals(orig.getStatus())) {
            return new StockTarget(orig);
        }
        InventoryBatch b = new InventoryBatch();
        b.setBusinessId(businessId);
        b.setBranchId(sale.getBranchId());
        b.setItemId(si.getItemId());
        b.setSupplierId(null);
        b.setBatchNumber("RR-" + refundId.replace("-", "").substring(0, 12));
        b.setSourceType(InventoryConstants.BATCH_SOURCE_REFUND_RETURN);
        b.setSourceId(refundId);
        b.setInitialQuantity(quantity);
        b.setQuantityRemaining(quantity);
        b.setUnitCost(si.getUnitCost().setScale(QTY_SCALE, RoundingMode.HALF_UP));
        b.setReceivedAt(Instant.now());
        b.setStatus(InventoryConstants.BATCH_STATUS_ACTIVE);
        inventoryBatchRepository.save(b);
        return new StockTarget(b);
    }

    private static void applyItemStock(Item item, BigDecimal delta) {
        BigDecimal base = item.getCurrentStock() == null ? BigDecimal.ZERO : item.getCurrentStock();
        item.setCurrentStock(base.add(delta).setScale(QTY_SCALE, RoundingMode.HALF_UP));
    }

    private String postRefundJournal(
            String businessId,
            String refundId,
            BigDecimal grandRefund,
            BigDecimal cogs,
            List<NormalizedPay> pays
    ) {
        ledgerBootstrapService.ensureStandardAccounts(businessId);
        LedgerAccount revenue = ledger(businessId, LedgerAccountCodes.SALES_REVENUE);
        LedgerAccount cogsAcc = ledger(businessId, LedgerAccountCodes.COST_OF_GOODS_SOLD);
        LedgerAccount inv = ledger(businessId, LedgerAccountCodes.INVENTORY);

        Map<String, BigDecimal> tenderCr = new LinkedHashMap<>();
        for (NormalizedPay p : pays) {
            String code = SalePaymentLedger.ledgerCodeForPaymentMethod(p.method());
            tenderCr.merge(code, p.amount(), BigDecimal::add);
        }

        JournalEntry je = new JournalEntry();
        je.setBusinessId(businessId);
        je.setEntryDate(LocalDate.now(ZoneOffset.UTC));
        je.setSourceType(SalesConstants.JOURNAL_SOURCE_SALE_REFUND);
        je.setSourceId(refundId);
        je.setMemo("Refund " + refundId);
        journalEntryRepository.save(je);

        grandRefund = grandRefund.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        cogs = cogs.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        List<JournalLine> lines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : tenderCr.entrySet()) {
            BigDecimal amt = e.getValue().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            lines.add(journalCredit(je.getId(), ledger(businessId, e.getKey()).getId(), amt));
        }
        lines.add(journalDebit(je.getId(), revenue.getId(), grandRefund));
        lines.add(journalCredit(je.getId(), cogsAcc.getId(), cogs));
        lines.add(journalDebit(je.getId(), inv.getId(), cogs));
        journalLineRepository.saveAll(lines);
        assertBalanced(lines);
        return je.getId();
    }

    private Refund persistRefundAggregate(
            String businessId,
            String saleId,
            String idemKey,
            String reason,
            String userId,
            RefundComputation comp,
            List<NormalizedPay> pays,
            String refundId,
            String journalId
    ) {
        Refund r = new Refund();
        r.setId(refundId);
        r.setBusinessId(businessId);
        r.setSaleId(saleId);
        r.setIdempotencyKey(idemKey);
        r.setJournalEntryId(journalId);
        r.setRefundedBy(userId);
        r.setTotalRefunded(comp.totalMoney());
        r.setReason(reason);
        r.setStatus(SalesConstants.REFUND_STATUS_COMPLETED);
        refundRepository.save(r);

        for (RefundRow row : comp.rows()) {
            RefundLine rl = new RefundLine();
            rl.setRefundId(refundId);
            rl.setSaleItemId(row.saleItem().getId());
            rl.setQuantity(row.quantity());
            rl.setAmount(row.money());
            refundLineRepository.save(rl);
        }
        int o = 0;
        for (NormalizedPay p : pays) {
            RefundPayment rp = new RefundPayment();
            rp.setRefundId(refundId);
            rp.setMethod(p.method());
            rp.setAmount(p.amount());
            rp.setReference(p.reference());
            rp.setSortOrder(o++);
            refundPaymentRepository.save(rp);
        }
        return r;
    }

    private void finalizeSaleRefundTotals(Sale sale, BigDecimal delta) {
        BigDecimal base = sale.getRefundedTotal() != null ? sale.getRefundedTotal() : BigDecimal.ZERO;
        BigDecimal next = base.add(delta).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        sale.setRefundedTotal(next);
        if (sale.getGrandTotal().subtract(next).abs().compareTo(TOLERANCE) <= 0) {
            sale.setStatus(SalesConstants.SALE_STATUS_REFUNDED);
        }
        saleRepository.save(sale);
    }

    private RefundResponse toRefundResponse(Refund refund) {
        List<RefundLine> rLines = refundLineRepository.findByRefundIdOrderByIdAsc(refund.getId());
        List<RefundLineResponse> lr = new ArrayList<>();
        for (RefundLine rl : rLines) {
            lr.add(new RefundLineResponse(rl.getSaleItemId(), rl.getQuantity(), rl.getAmount()));
        }
        List<RefundPayment> rPay = refundPaymentRepository.findByRefundIdOrderBySortOrderAsc(refund.getId());
        List<RefundPaymentResponse> pr = new ArrayList<>();
        for (RefundPayment p : rPay) {
            pr.add(new RefundPaymentResponse(p.getMethod(), p.getAmount(), p.getReference()));
        }
        Sale sale = saleRepository.findByIdAndBusinessId(refund.getSaleId(), refund.getBusinessId()).orElseThrow();
        var items = saleItemRepository.findBySaleIdOrderByLineIndexAsc(sale.getId());
        var pays = salePaymentRepository.findBySaleIdOrderBySortOrderAsc(sale.getId());
        return new RefundResponse(
                refund.getId(),
                refund.getSaleId(),
                refund.getTotalRefunded(),
                refund.getJournalEntryId(),
                refund.getRefundedAt(),
                refund.getReason(),
                lr,
                pr,
                SaleResponseMapper.map(sale, items, pays)
        );
    }

    private LedgerAccount ledger(String businessId, String code) {
        return ledgerAccountRepository.findByBusinessIdAndCode(businessId, code)
                .orElseThrow(() -> new IllegalStateException("Missing ledger account " + code));
    }

    private static void assertBalanced(List<JournalLine> lines) {
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (JournalLine l : lines) {
            dr = dr.add(l.getDebit());
            cr = cr.add(l.getCredit());
        }
        if (dr.subtract(cr).abs().compareTo(TOLERANCE) > 0) {
            throw new IllegalStateException("Unbalanced refund journal");
        }
    }

    private static JournalLine journalDebit(String entryId, String accId, BigDecimal amount) {
        JournalLine l = new JournalLine();
        l.setJournalEntryId(entryId);
        l.setLedgerAccountId(accId);
        l.setDebit(amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        l.setCredit(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        return l;
    }

    private static JournalLine journalCredit(String entryId, String accId, BigDecimal amount) {
        JournalLine l = new JournalLine();
        l.setJournalEntryId(entryId);
        l.setLedgerAccountId(accId);
        l.setDebit(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        l.setCredit(amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        return l;
    }

    private static List<NormalizedPay> normalizeRefundPayments(
            List<PostRefundRequest.PostRefundPaymentRequest> payments,
            BigDecimal grandTotal
    ) {
        List<NormalizedPay> out = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (PostRefundRequest.PostRefundPaymentRequest p : payments) {
            String m = normalizeMethod(p.method());
            BigDecimal amt = p.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            String ref = blankToNull(p.reference());
            if (SalesConstants.PAYMENT_METHOD_MPESA_MANUAL.equals(m) && ref == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "M-Pesa manual refunds require a reference");
            }
            if (refNotAllowed(m) && ref != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund tender does not use a payment reference");
            }
            sum = sum.add(amt);
            out.add(new NormalizedPay(m, amt, ref));
        }
        if (sum.setScale(MONEY_SCALE, RoundingMode.HALF_UP).compareTo(grandTotal) != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund payments must sum to line totals");
        }
        return out;
    }

    private static boolean refNotAllowed(String m) {
        return SalesConstants.PAYMENT_METHOD_CUSTOMER_CREDIT.equals(m)
                || SalesConstants.PAYMENT_METHOD_CUSTOMER_WALLET.equals(m)
                || SalesConstants.PAYMENT_METHOD_LOYALTY_REDEEM.equals(m);
    }

    private static BigDecimal sumPayMethod(List<NormalizedPay> pays, String method) {
        BigDecimal s = BigDecimal.ZERO;
        for (NormalizedPay p : pays) {
            if (method.equals(p.method())) {
                s = s.add(p.amount());
            }
        }
        return s.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal sumCash(List<NormalizedPay> pays) {
        BigDecimal s = BigDecimal.ZERO;
        for (NormalizedPay p : pays) {
            if (SalesConstants.PAYMENT_METHOD_CASH.equals(p.method())) {
                s = s.add(p.amount());
            }
        }
        return s.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String normalizeMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment method required");
        }
        String m = raw.trim().toLowerCase(Locale.ROOT);
        if (!SalesConstants.PAYMENT_METHOD_CASH.equals(m)
                && !SalesConstants.PAYMENT_METHOD_MPESA_MANUAL.equals(m)
                && !SalesConstants.PAYMENT_METHOD_CUSTOMER_CREDIT.equals(m)
                && !SalesConstants.PAYMENT_METHOD_CUSTOMER_WALLET.equals(m)
                && !SalesConstants.PAYMENT_METHOD_LOYALTY_REDEEM.equals(m)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported payment method");
        }
        return m;
    }

    private static void assertWalletRefundAllowed(Sale sale, BigDecimal walletPortion) {
        if (walletPortion.signum() <= 0) {
            return;
        }
        if (sale.getCustomerId() == null || sale.getCustomerId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer required for wallet refund tender");
        }
    }

    private static void assertCreditRefundAllowed(Sale sale, List<NormalizedPay> pays) {
        if (sumCustomerCreditPay(pays).signum() <= 0) {
            return;
        }
        if (sale.getCustomerId() == null || sale.getCustomerId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer required for tab refund");
        }
    }

    private static BigDecimal sumCustomerCreditPay(List<NormalizedPay> pays) {
        BigDecimal s = BigDecimal.ZERO;
        for (NormalizedPay p : pays) {
            if (SalesConstants.PAYMENT_METHOD_CUSTOMER_CREDIT.equals(p.method())) {
                s = s.add(p.amount());
            }
        }
        return s.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String normalizeIdempotencyKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header required");
        }
        return raw.trim();
    }

    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private record NormalizedPay(String method, BigDecimal amount, String reference) {
    }

    private record RefundRow(SaleItem saleItem, BigDecimal quantity, BigDecimal money, BigDecimal cogs) {
    }

    private record RefundComputation(List<RefundRow> rows, BigDecimal totalMoney, BigDecimal totalCogs) {
    }

    private record StockTarget(InventoryBatch batch) {
    }
}
