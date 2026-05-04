package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerBootstrapService;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.finance.domain.LedgerAccount;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.JournalLineRepository;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.BatchAllocationLine;
import zelisline.ub.inventory.application.InventoryBatchPickerService;
import zelisline.ub.credits.application.BusinessCreditSettingsService;
import zelisline.ub.credits.application.CreditSaleDebtService;
import zelisline.ub.credits.application.LoyaltyPointsService;
import zelisline.ub.credits.application.WalletLedgerService;
import zelisline.ub.integrations.webhook.WebhookEventTypes;
import zelisline.ub.integrations.webhook.application.WebhookEnqueueService;
import zelisline.ub.sales.SalePaymentLedger;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.PostSaleLineRequest;
import zelisline.ub.sales.api.dto.PostSalePaymentRequest;
import zelisline.ub.sales.api.dto.PostSaleRequest;
import zelisline.ub.sales.api.dto.SaleResponse;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class SaleService {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final int MONEY_SCALE = 2;
    private static final int QTY_SCALE = 4;
    private static final long MAX_CLIENT_SOLD_AT_SKEW_SECONDS = 3600;

    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final SalePaymentRepository salePaymentRepository;
    private final ShiftRepository shiftRepository;
    private final BranchRepository branchRepository;
    private final InventoryBatchPickerService inventoryBatchPickerService;
    private final LedgerBootstrapService ledgerBootstrapService;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;
    private final CreditSaleDebtService creditSaleDebtService;
    private final WalletLedgerService walletLedgerService;
    private final LoyaltyPointsService loyaltyPointsService;
    private final BusinessCreditSettingsService businessCreditSettingsService;
    private final WebhookEnqueueService webhookEnqueueService;

    @Transactional
    public SaleCreationOutcome createSale(String businessId, String rawIdempotencyKey, PostSaleRequest req, String userId) {
        String idempotencyKey = normalizeIdempotencyKey(rawIdempotencyKey);
        var existing = saleRepository.findByBusinessIdAndIdempotencyKey(businessId, idempotencyKey);
        if (existing.isPresent()) {
            return new SaleCreationOutcome(toResponse(existing.get()), false);
        }
        return new SaleCreationOutcome(completeNewSale(businessId, idempotencyKey, req, userId), true);
    }

    private SaleResponse completeNewSale(String businessId, String idempotencyKey, PostSaleRequest req, String userId) {
        var creditSettingsResolved = businessCreditSettingsService.resolveForBusiness(businessId);
        requireBranch(businessId, req.branchId());
        BigDecimal grandTotal = computeCartTotal(req.lines());
        validatePositiveMoney(grandTotal);
        ResolvedPayments resolved = normalizeAndResolvePayments(req.payments(), grandTotal);
        BigDecimal creditTenderTotal = sumPaymentMethod(resolved.normalized(), SalesConstants.PAYMENT_METHOD_CUSTOMER_CREDIT);
        BigDecimal walletTenderTotal = sumPaymentMethod(resolved.normalized(), SalesConstants.PAYMENT_METHOD_CUSTOMER_WALLET);
        BigDecimal redeemTenderTotal = sumPaymentMethod(resolved.normalized(), SalesConstants.PAYMENT_METHOD_LOYALTY_REDEEM);

        String customerId = blankToNull(req.customerId());
        enforceCustomerLinkage(customerId, creditTenderTotal, walletTenderTotal, redeemTenderTotal, resolved.overpay());
        validateCustomerForCreditSale(businessId, customerId, creditTenderTotal);
        loyaltyPointsService.validateRedeemTender(businessId, customerId, grandTotal, redeemTenderTotal,
                creditSettingsResolved);

        shiftRepository
                .findByBusinessIdAndBranchIdAndStatus(businessId, req.branchId(), SalesConstants.SHIFT_STATUS_OPEN)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No open shift"));

        String saleId = UUID.randomUUID().toString();
        List<SaleItem> saleItems = pickAndBuildSaleItems(businessId, req, saleId, userId);

        Shift shift = shiftRepository
                .findByBusinessIdAndBranchIdAndStatusForUpdate(businessId, req.branchId(), SalesConstants.SHIFT_STATUS_OPEN)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Shift closed before sale completed"));

        BigDecimal cogsTotal = sumCost(saleItems);
        Instant effectiveSoldAt = resolveEffectiveSoldAt(req.clientSoldAt());

        saveNewSaleAndPayments(
                businessId,
                req.branchId(),
                shift.getId(),
                idempotencyKey,
                saleId,
                grandTotal,
                userId,
                resolved.normalized(),
                effectiveSoldAt,
                customerId
        );
        saleItemRepository.saveAll(saleItems);

        String journalId = postSaleJournal(businessId, saleId, grandTotal, cogsTotal,
                resolved.normalized(), resolved.overpay());
        attachJournalToSale(saleId, businessId, journalId);
        creditSaleDebtService.applyDebtForNewSale(businessId, saleId, customerId, creditTenderTotal);
        walletLedgerService.applyWalletForCompletedSale(
                businessId, saleId, customerId, walletTenderTotal, resolved.overpay());
        loyaltyPointsService.applyAfterCompletedSale(
                businessId, customerId, saleId, grandTotal, redeemTenderTotal, creditSettingsResolved);

        BigDecimal cashIn = sumCashTender(resolved.normalized());
        if (cashIn.signum() > 0) {
            applyDrawerCash(shift, cashIn);
        }
        shiftRepository.save(shift);

        SaleResponse completed = toResponse(loadSaleOrThrow(saleId, businessId));
        enqueueSaleCompletedWebhook(businessId, completed, effectiveSoldAt);
        return completed;
    }

    private void enqueueSaleCompletedWebhook(String businessId, SaleResponse sale, Instant soldAt) {
        var data = new LinkedHashMap<String, Object>();
        data.put("saleId", sale.id());
        data.put("branchId", sale.branchId());
        data.put("customerId", sale.customerId());
        data.put("grandTotal", sale.grandTotal().toPlainString());
        if (soldAt != null) {
            data.put("soldAt", soldAt.toString());
        }
        webhookEnqueueService.enqueue(
                businessId,
                WebhookEventTypes.SALE_COMPLETED,
                data,
                "sale.completed:" + sale.id());
    }

    private void enforceCustomerLinkage(
            String customerId,
            BigDecimal creditTenderTotal,
            BigDecimal walletTenderTotal,
            BigDecimal redeemTenderTotal,
            BigDecimal overpay
    ) {
        boolean walletNeed = walletTenderTotal.signum() > 0 || overpay.signum() > 0;
        boolean need = creditTenderTotal.signum() > 0 || walletNeed || redeemTenderTotal.signum() > 0;
        if (!need) {
            return;
        }
        if (customerId == null || customerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer required for this tender mix");
        }
    }

    private record ResolvedPayments(List<NormalizedPayment> normalized, BigDecimal overpay) {
    }

    private void saveNewSaleAndPayments(
            String businessId,
            String branchId,
            String shiftId,
            String idempotencyKey,
            String saleId,
            BigDecimal grandTotal,
            String userId,
            List<NormalizedPayment> paymentsNorm,
            Instant soldAt,
            String customerIdOrNull
    ) {
        Sale sale = new Sale();
        sale.setId(saleId);
        sale.setBusinessId(businessId);
        sale.setBranchId(branchId);
        sale.setShiftId(shiftId);
        sale.setStatus(SalesConstants.SALE_STATUS_COMPLETED);
        sale.setIdempotencyKey(idempotencyKey);
        sale.setGrandTotal(grandTotal);
        sale.setJournalEntryId(null);
        sale.setSoldBy(userId);
        sale.setSoldAt(soldAt);
        if (customerIdOrNull != null) {
            sale.setCustomerId(customerIdOrNull);
        }
        saleRepository.save(sale);

        int order = 0;
        for (NormalizedPayment p : paymentsNorm) {
            SalePayment row = new SalePayment();
            row.setSaleId(saleId);
            row.setMethod(p.method());
            row.setAmount(p.amount());
            row.setReference(p.reference());
            row.setSortOrder(order++);
            salePaymentRepository.save(row);
        }
    }

    private void attachJournalToSale(String saleId, String businessId, String journalId) {
        Sale sale = loadSaleOrThrow(saleId, businessId);
        sale.setJournalEntryId(journalId);
        saleRepository.save(sale);
    }

    private void validateCustomerForCreditSale(String businessId, String customerId, BigDecimal creditTenderTotal) {
        if (creditTenderTotal.signum() <= 0) {
            return;
        }
        if (customerId == null || customerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer required for tab tender");
        }
        creditSaleDebtService.assertCustomerExists(businessId, customerId);
    }

    private Sale loadSaleOrThrow(String saleId, String businessId) {
        return saleRepository.findByIdAndBusinessId(saleId, businessId)
                .orElseThrow(() -> new IllegalStateException("Sale not found after insert"));
    }

    private static Instant resolveEffectiveSoldAt(Instant clientSoldAt) {
        Instant serverNow = Instant.now();
        if (clientSoldAt == null) {
            return serverNow;
        }
        long skewSeconds = Math.abs(Duration.between(clientSoldAt, serverNow).getSeconds());
        if (skewSeconds > MAX_CLIENT_SOLD_AT_SKEW_SECONDS) {
            return serverNow;
        }
        return clientSoldAt;
    }

    private static void applyDrawerCash(Shift shift, BigDecimal cashIn) {
        BigDecimal next = shift.getExpectedClosingCash().add(cashIn).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        shift.setExpectedClosingCash(next);
    }

    private List<SaleItem> pickAndBuildSaleItems(
            String businessId,
            PostSaleRequest req,
            String saleId,
            String userId
    ) {
        List<SaleItem> all = new ArrayList<>();
        int lineIndex = 0;
        for (PostSaleLineRequest line : req.lines()) {
            BigDecimal qty = line.quantity().setScale(QTY_SCALE, RoundingMode.HALF_UP);
            List<BatchAllocationLine> allocations = inventoryBatchPickerService.pickAndApplyPhysicalDecrement(
                    businessId,
                    line.itemId(),
                    req.branchId(),
                    qty,
                    SalesConstants.STOCK_REFERENCE_TYPE_SALE,
                    saleId,
                    InventoryConstants.MOVEMENT_SALE,
                    userId
            );
            all.addAll(buildItemsForCartLine(saleId, lineIndex, line, allocations));
            lineIndex++;
        }
        return all;
    }

    private static List<SaleItem> buildItemsForCartLine(
            String saleId,
            int lineIndex,
            PostSaleLineRequest line,
            List<BatchAllocationLine> allocations
    ) {
        BigDecimal lineQty = line.quantity().setScale(QTY_SCALE, RoundingMode.HALF_UP);
        BigDecimal lineTotal = lineQty.multiply(line.unitPrice()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal revenueAllocated = BigDecimal.ZERO;
        List<SaleItem> rows = new ArrayList<>();
        for (int i = 0; i < allocations.size(); i++) {
            BatchAllocationLine a = allocations.get(i);
            BigDecimal portion = revenuePortion(lineTotal, lineQty, revenueAllocated, i, allocations.size(), a.quantity());
            revenueAllocated = revenueAllocated.add(portion);
            BigDecimal costTotal = a.quantity().multiply(a.unitCost()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal profit = portion.subtract(costTotal);
            SaleItem row = new SaleItem();
            row.setSaleId(saleId);
            row.setLineIndex(lineIndex);
            row.setItemId(line.itemId());
            row.setBatchId(a.batchId());
            row.setQuantity(a.quantity().setScale(QTY_SCALE, RoundingMode.HALF_UP));
            row.setUnitPrice(line.unitPrice().setScale(QTY_SCALE, RoundingMode.HALF_UP));
            row.setLineTotal(portion);
            row.setUnitCost(a.unitCost().setScale(QTY_SCALE, RoundingMode.HALF_UP));
            row.setCostTotal(costTotal);
            row.setProfit(profit);
            rows.add(row);
        }
        return rows;
    }

    private static BigDecimal revenuePortion(
            BigDecimal lineTotal,
            BigDecimal lineQty,
            BigDecimal allocatedSoFar,
            int index,
            int n,
            BigDecimal allocQty
    ) {
        if (index == n - 1) {
            return lineTotal.subtract(allocatedSoFar);
        }
        return lineTotal.multiply(allocQty).divide(lineQty, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal sumCost(List<SaleItem> saleItems) {
        BigDecimal t = BigDecimal.ZERO;
        for (SaleItem si : saleItems) {
            t = t.add(si.getCostTotal());
        }
        return t.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private String postSaleJournal(
            String businessId,
            String saleId,
            BigDecimal grandTotal,
            BigDecimal cogs,
            List<NormalizedPayment> paymentsNorm,
            BigDecimal overpayToWallet
    ) {
        ledgerBootstrapService.ensureStandardAccounts(businessId);
        LedgerAccount revenue = ledger(businessId, LedgerAccountCodes.SALES_REVENUE);
        LedgerAccount cogsAcc = ledger(businessId, LedgerAccountCodes.COST_OF_GOODS_SOLD);
        LedgerAccount inv = ledger(businessId, LedgerAccountCodes.INVENTORY);

        Map<String, BigDecimal> tenderDr = new LinkedHashMap<>();
        for (NormalizedPayment p : paymentsNorm) {
            String code = SalePaymentLedger.ledgerCodeForPaymentMethod(p.method());
            tenderDr.merge(code, p.amount(), BigDecimal::add);
        }

        JournalEntry je = new JournalEntry();
        je.setBusinessId(businessId);
        je.setEntryDate(LocalDate.now(ZoneOffset.UTC));
        je.setSourceType(SalesConstants.JOURNAL_SOURCE_SALE);
        je.setSourceId(saleId);
        je.setMemo("POS sale " + saleId);
        journalEntryRepository.save(je);

        grandTotal = grandTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        cogs = cogs.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal overpay = overpayToWallet.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        List<JournalLine> lines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : tenderDr.entrySet()) {
            BigDecimal amt = e.getValue().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            lines.add(journalDebit(je.getId(), ledger(businessId, e.getKey()).getId(), amt));
        }
        lines.add(journalCredit(je.getId(), revenue.getId(), grandTotal));
        if (overpay.signum() > 0) {
            LedgerAccount wl = ledger(businessId, LedgerAccountCodes.CUSTOMER_WALLET_LIABILITY);
            lines.add(journalCredit(je.getId(), wl.getId(), overpay));
        }
        lines.add(journalDebit(je.getId(), cogsAcc.getId(), cogs));
        lines.add(journalCredit(je.getId(), inv.getId(), cogs));
        journalLineRepository.saveAll(lines);
        assertBalanced(lines);
        return je.getId();
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
            throw new IllegalStateException("Unbalanced journal");
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

    private SaleResponse toResponse(Sale sale) {
        List<SaleItem> items = saleItemRepository.findBySaleIdOrderByLineIndexAsc(sale.getId());
        List<SalePayment> pays = salePaymentRepository.findBySaleIdOrderBySortOrderAsc(sale.getId());
        return SaleResponseMapper.map(sale, items, pays);
    }

    private static BigDecimal computeCartTotal(List<PostSaleLineRequest> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (PostSaleLineRequest l : lines) {
            BigDecimal line = l.quantity()
                    .multiply(l.unitPrice())
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            total = total.add(line);
        }
        return total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static void validatePositiveMoney(BigDecimal grandTotal) {
        if (grandTotal.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Grand total must be positive");
        }
    }

    private ResolvedPayments normalizeAndResolvePayments(
            List<PostSalePaymentRequest> payments,
            BigDecimal grandTotal
    ) {
        List<NormalizedPayment> out = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal gp = grandTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        for (PostSalePaymentRequest p : payments) {
            String m = normalizePaymentMethod(p.method());
            BigDecimal amt = p.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            String ref = blankToNull(p.reference());
            if (SalesConstants.PAYMENT_METHOD_MPESA_MANUAL.equals(m) && ref == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "M-Pesa manual payments require a reference");
            }
            forbidReferenceOnNonReferenceTender(m, ref);
            sum = sum.add(amt);
            out.add(new NormalizedPayment(m, amt, ref));
        }
        sum = sum.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (sum.compareTo(gp) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payments must cover line totals");
        }
        BigDecimal overpay = sum.subtract(gp).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new ResolvedPayments(out, overpay);
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

    private static BigDecimal sumPaymentMethod(List<NormalizedPayment> paymentsNorm, String method) {
        BigDecimal s = BigDecimal.ZERO;
        for (NormalizedPayment p : paymentsNorm) {
            if (method.equals(p.method())) {
                s = s.add(p.amount());
            }
        }
        return s.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal sumCashTender(List<NormalizedPayment> paymentsNorm) {
        BigDecimal s = BigDecimal.ZERO;
        for (NormalizedPayment p : paymentsNorm) {
            if (SalesConstants.PAYMENT_METHOD_CASH.equals(p.method())) {
                s = s.add(p.amount());
            }
        }
        return s.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private record NormalizedPayment(String method, BigDecimal amount, String reference) {
    }

    private static String normalizePaymentMethod(String raw) {
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

    private static String normalizeIdempotencyKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header required");
        }
        return raw.trim();
    }

    private void requireBranch(String businessId, String branchId) {
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
    }

    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }
}
