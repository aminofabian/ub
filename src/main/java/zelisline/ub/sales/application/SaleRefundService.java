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
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.inventory.WastageReason;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.inventory.application.BatchNumberGenerator;
import zelisline.ub.credits.application.BusinessCreditSettingsService;
import zelisline.ub.credits.application.CreditSaleDebtService;
import zelisline.ub.credits.application.LoyaltyPointsService;
import zelisline.ub.credits.application.WalletLedgerService;
import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerAccountResolver;
import zelisline.ub.finance.application.LedgerPostingPort;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.domain.SupplyBatch;
import zelisline.ub.inventory.repository.SupplyBatchRepository;
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
    private static final int WEIGHTED_QTY_SCALE = 3;
    private static final String WEIGHED_REFUND_PERMISSION = "sales.weighed.refund";

    private final BatchNumberGenerator batchNumberGenerator;

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
    private final SupplyBatchRepository supplyBatchRepository;
    private final LedgerPostingPort ledgerPostingPort;
    private final LedgerAccountResolver ledgerAccountResolver;
    private final CreditSaleDebtService creditSaleDebtService;
    private final WalletLedgerService walletLedgerService;
    private final LoyaltyPointsService loyaltyPointsService;
    private final BusinessCreditSettingsService businessCreditSettingsService;
    private final SaleActorNameService saleActorNameService;
    private final PackageVariantStockResolver packageVariantStockResolver;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;
    private final RequestPermissionService requestPermissionService;
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
        return createRefund(businessId, saleId, rawIdempotencyKey, req, userId, null);
    }

    @Transactional
    public RefundResponse createRefund(
            String businessId,
            String saleId,
            String rawIdempotencyKey,
            PostRefundRequest req,
            String userId,
            String roleId
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
        assertWeighedRefundAllowed(comp.rows(), roleId);
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

        String jeId = postRefundJournal(businessId, refundId, comp, pays);
        creditSaleDebtService.reduceDebtForCreditRefund(
                businessId, saleId, sale.getCustomerId(), sumCustomerCreditPay(pays));
        walletLedgerService.refundToWallet(businessId, saleId, sale.getCustomerId(), walletRefundShare);
        loyaltyPointsService.proportionallyAdjustAfterRefund(
                businessId,
                saleId,
                sale.getCustomerId(),
                sale.getGrandTotal(),
                comp.totalMoney(),
                businessCreditSettingsService.resolveForBusiness(businessId));

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
        publishRefundEvents(businessId, sale, refund, shift, userId, pays);

        return toRefundResponse(refund);
    }

    private void publishRefundEvents(String businessId, Sale sale, Refund refund, Shift shift, String userId, List<NormalizedPay> pays) {
        BigDecimal cashOut = sumCash(pays);
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.SALES, AuditEventTypes.REFUND_ISSUED, AuditEventSeverity.INFO)
                .businessId(businessId)
                .branchId(sale.getBranchId())
                .actor(userId, AuditEventActorType.USER)
                .target("refund", refund.getId())
                .targetLabel("Refund " + refund.getId().substring(0, 8))
                .shiftId(shift.getId())
                .newState(map(
                        "saleId", sale.getId(),
                        "totalRefunded", refund.getTotalRefunded().toPlainString(),
                        "paymentMethods", paymentMethodSummary(pays)))
                .source("pos_terminal")
                .reason(refund.getReason())
                .build());

        if (cashOut.signum() > 0) {
            auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.CASH_DRAWER, AuditEventTypes.CASH_REFUND_REMOVED, AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .branchId(sale.getBranchId())
                    .actor(userId, AuditEventActorType.USER)
                    .target("shift", shift.getId())
                    .targetLabel("Shift " + shift.getId().substring(0, 8))
                    .shiftId(shift.getId())
                    .newState(map("cashAmount", cashOut.toPlainString()))
                    .source("pos_terminal")
                    .reason("Refund: " + refund.getId())
                    .build());
        }
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }

    private static Map<String, BigDecimal> paymentMethodSummary(List<NormalizedPay> pays) {
        Map<String, BigDecimal> summary = new LinkedHashMap<>();
        for (NormalizedPay p : pays) {
            summary.merge(p.method(), p.amount(), BigDecimal::add);
        }
        return summary;
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
            Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(si.getItemId(), sale.getBusinessId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
            boolean weighed = item.isWeighed();
            BigDecimal q = line.quantity().setScale(QTY_SCALE, RoundingMode.HALF_UP);
            if (q.signum() <= 0 || q.compareTo(si.getQuantity()) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid refund quantity for line");
            }
            if (weighed && line.quantity().scale() > WEIGHTED_QTY_SCALE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Weighed refund quantity may have at most " + WEIGHTED_QTY_SCALE + " decimal places");
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
            rows.add(new RefundRow(si, item, q, money, cogs, weighed));
        }

        totalMoney = totalMoney.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        totalCogs = totalCogs.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (totalMoney.compareTo(cap) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund total exceeds remaining sale total");
        }
        return new RefundComputation(rows, totalMoney, totalCogs);
    }

    private void assertWeighedRefundAllowed(List<RefundRow> rows, String roleId) {
        boolean hasWeighed = rows.stream().anyMatch(RefundRow::weighed);
        if (!hasWeighed) {
            return;
        }
        if (roleId == null || !requestPermissionService.hasPermission(roleId, WEIGHED_REFUND_PERMISSION)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Weighed refund requires manager approval");
        }
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
        String lastStockHolderId = null;
        for (RefundRow row : sorted) {
            SaleItem si = row.saleItem();
            Item sold = row.item();
            if (row.weighed()) {
                recordWeighedRefundWastageMovement(businessId, sale, refundId, si, row.quantity(), userId);
                continue;
            }
            String stockHolderId = packageVariantStockResolver.stockHolderItemId(sold);
            if (!stockHolderId.equals(lastStockHolderId)) {
                if (item != null) {
                    itemRepository.save(item);
                }
                item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(stockHolderId, businessId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
                entityManager.lock(item, LockModeType.PESSIMISTIC_WRITE);
                lastStockHolderId = stockHolderId;
            }
            BigDecimal q = row.quantity();
            StockTarget target = resolveReturnBatch(businessId, sale, refundId, si, q);
            target.batch().setQuantityRemaining(
                    target.batch().getQuantityRemaining().add(q).setScale(QTY_SCALE, RoundingMode.HALF_UP));
            inventoryBatchRepository.save(target.batch());

            StockMovement sm = new StockMovement();
            sm.setBusinessId(businessId);
            sm.setBranchId(sale.getBranchId());
            sm.setItemId(lastStockHolderId);
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

    private void recordWeighedRefundWastageMovement(
            String businessId,
            Sale sale,
            String refundId,
            SaleItem si,
            BigDecimal quantity,
            String userId
    ) {
        StockMovement sm = new StockMovement();
        sm.setBusinessId(businessId);
        sm.setBranchId(sale.getBranchId());
        sm.setItemId(packageVariantStockResolver.stockHolderItemId(
                itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(si.getItemId(), businessId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"))));
        sm.setBatchId(si.getBatchId());
        sm.setMovementType(InventoryConstants.MOVEMENT_REFUND_WASTAGE);
        sm.setReferenceType(SalesConstants.STOCK_REFERENCE_TYPE_SALE_REFUND);
        sm.setReferenceId(refundId);
        sm.setQuantityDelta(BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP));
        sm.setUnitCost(si.getUnitCost());
        sm.setNotes("Weighed refund — returned stock written off (" + quantity.stripTrailingZeros().toPlainString() + ")");
        sm.setWastageReason(WastageReason.CUSTOMER_RETURN.name());
        sm.setCreatedBy(userId);
        stockMovementRepository.save(sm);
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
        // ── Resolve supplier from the ORIGINAL sale item's batch ──────
        String supplierId = resolveSupplierFromOriginalBatch(businessId, si);

        SupplyBatch sb = new SupplyBatch();
        sb.setBusinessId(businessId);
        sb.setBranchId(sale.getBranchId());
        sb.setSupplierId(supplierId);
        sb.setBatchNumber(batchNumberGenerator.next(null, null, Instant.now(), businessId));
        sb.setBatchName("Return from sale " + sale.getId().substring(0, 8));
        sb.setSourceType(InventoryConstants.BATCH_SOURCE_REFUND_RETURN);
        sb.setSourceId(refundId);
        sb.setItemCount(1);
        sb.setTotalInitialQuantity(quantity);
        sb.setTotalRemainingQuantity(quantity);
        sb.setReceivedAt(Instant.now());
        sb.setStatus("active");
        supplyBatchRepository.save(sb);

        InventoryBatch b = new InventoryBatch();
        b.setBusinessId(businessId);
        b.setBranchId(sale.getBranchId());
        Item sold = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(si.getItemId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
        b.setItemId(packageVariantStockResolver.stockHolderItemId(sold));
        b.setSupplyBatchId(sb.getId());
        b.setSupplierId(supplierId);
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

    /**
     * Traces the supplier from the original batch that the sale item was sold from.
     * Reads the batch WITHOUT locking (read-only lookup).
     */
    private String resolveSupplierFromOriginalBatch(String businessId, SaleItem si) {
        return inventoryBatchRepository
                .findByIdAndBusinessId(si.getBatchId(), businessId)
                .map(InventoryBatch::getSupplierId)
                .orElse(null);
    }

    private static void applyItemStock(Item item, BigDecimal delta) {
        BigDecimal base = item.getCurrentStock() == null ? BigDecimal.ZERO : item.getCurrentStock();
        item.setCurrentStock(base.add(delta).setScale(QTY_SCALE, RoundingMode.HALF_UP));
    }

    private String postRefundJournal(
            String businessId,
            String refundId,
            RefundComputation comp,
            List<NormalizedPay> pays
    ) {
        Map<String, BigDecimal> tenderCr = new LinkedHashMap<>();
        for (NormalizedPay p : pays) {
            String code = SalePaymentLedger.ledgerCodeForPaymentMethod(p.method());
            tenderCr.merge(code, p.amount(), BigDecimal::add);
        }

        BigDecimal weighedCogs = BigDecimal.ZERO;
        for (RefundRow row : comp.rows()) {
            if (row.weighed()) {
                weighedCogs = weighedCogs.add(row.cogs());
            }
        }
        weighedCogs = weighedCogs.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal nonWeighedCogs = comp.totalCogs().subtract(weighedCogs).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        JournalEntry entry = new JournalEntry();
        entry.setBusinessId(businessId);
        entry.setEntryDate(LocalDate.now(ZoneOffset.UTC));
        entry.setSourceType(SalesConstants.JOURNAL_SOURCE_SALE_REFUND);
        entry.setSourceId(refundId);
        entry.setMemo("Refund " + refundId);

        for (Map.Entry<String, BigDecimal> e : tenderCr.entrySet()) {
            BigDecimal amt = e.getValue().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            entry.credit(ledgerAccountResolver.resolveId(businessId, e.getKey()), amt);
        }
        entry.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.SALES_REVENUE), comp.totalMoney());
        entry.credit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.COST_OF_GOODS_SOLD), comp.totalCogs());
        if (nonWeighedCogs.signum() != 0) {
            entry.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.INVENTORY), nonWeighedCogs);
        }
        if (weighedCogs.signum() != 0) {
            entry.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.INVENTORY_SHRINKAGE), weighedCogs);
        }

        return ledgerPostingPort.post(entry);
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
                SaleResponseMapper.map(
                        sale,
                        items,
                        pays,
                        saleActorNameService.resolveSoldByName(sale.getBusinessId(), sale.getSoldBy())
                )
        );
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
                && !SalesConstants.PAYMENT_METHOD_CARD.equals(m)
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

    private record RefundRow(SaleItem saleItem, Item item, BigDecimal quantity, BigDecimal money, BigDecimal cogs, boolean weighed) {
    }

    private record RefundComputation(List<RefundRow> rows, BigDecimal totalMoney, BigDecimal totalCogs) {
    }

    private record StockTarget(InventoryBatch batch) {
    }
}
