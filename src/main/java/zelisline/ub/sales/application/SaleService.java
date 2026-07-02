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
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerAccountResolver;
import zelisline.ub.finance.application.LedgerPostingPort;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.BatchAllocationLine;
import zelisline.ub.inventory.application.InventoryBatchPickerService;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.credits.application.BusinessCreditSettingsService;
import zelisline.ub.credits.application.CreditSaleDebtService;
import zelisline.ub.credits.application.LoyaltyPointsService;
import zelisline.ub.credits.application.WalletLedgerService;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.integrations.webhook.WebhookEventTypes;
import zelisline.ub.integrations.webhook.application.WebhookEnqueueService;
import zelisline.ub.messaging.application.CreditSaleReminderEvent;
import zelisline.ub.messaging.application.CreditSaleReminderLineItem;
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
    private static final String PRICE_OVERRIDE_PERMISSION = "pricing.sell_price.set";

    /** Supported weight units for weighed sale items (v1 = kg only). */
    private static final Set<String> WEIGHT_UNITS = Set.of("kg", "g", "lb");
    /** Maximum decimal scale for kg/lb weight entry at the POS. */
    private static final int WEIGHTED_QTY_SCALE = 3;

    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final SalePaymentRepository salePaymentRepository;
    private final ShiftRepository shiftRepository;
    private final BranchRepository branchRepository;
    private final InventoryBatchPickerService inventoryBatchPickerService;
    private final LedgerPostingPort ledgerPostingPort;
    private final LedgerAccountResolver ledgerAccountResolver;
    private final CreditSaleDebtService creditSaleDebtService;
    private final WalletLedgerService walletLedgerService;
    private final LoyaltyPointsService loyaltyPointsService;
    private final BusinessCreditSettingsService businessCreditSettingsService;
    private final WebhookEnqueueService webhookEnqueueService;
    private final ApplicationEventPublisher eventPublisher;
    private final SaleActorNameService saleActorNameService;
    private final ItemRepository itemRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;
    private final PricingService pricingService;
    private final RequestPermissionService requestPermissionService;

    @Transactional
    public SaleCreationOutcome createSale(String businessId, String rawIdempotencyKey, PostSaleRequest req, String userId) {
        return createSale(businessId, rawIdempotencyKey, req, userId, null);
    }

    @Transactional
    public SaleCreationOutcome createSale(String businessId, String rawIdempotencyKey, PostSaleRequest req, String userId, String roleId) {
        String idempotencyKey = normalizeIdempotencyKey(rawIdempotencyKey);
        var existing = saleRepository.findByBusinessIdAndIdempotencyKey(businessId, idempotencyKey);
        if (existing.isPresent()) {
            return new SaleCreationOutcome(toResponse(existing.get()), false);
        }
        return new SaleCreationOutcome(completeNewSale(businessId, idempotencyKey, req, userId, roleId), true);
    }

    @Transactional(readOnly = true)
    public SaleResponse requireSale(String businessId, String saleId) {
        Sale sale = saleRepository.findByIdAndBusinessId(saleId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale not found"));
        return toResponse(sale);
    }

    private SaleResponse completeNewSale(String businessId, String idempotencyKey, PostSaleRequest req, String userId) {
        return completeNewSale(businessId, idempotencyKey, req, userId, null);
    }

    private SaleResponse completeNewSale(String businessId, String idempotencyKey, PostSaleRequest req, String userId, String roleId) {
        var creditSettingsResolved = businessCreditSettingsService.resolveForBusiness(businessId);
        requireBranch(businessId, req.branchId());
        validateSaleLines(businessId, req.branchId(), req.lines(), roleId);
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
        if (creditTenderTotal.signum() > 0 && customerId != null && !customerId.isBlank()) {
            eventPublisher.publishEvent(buildCreditSaleReminderEvent(
                    businessId, saleId, customerId, creditTenderTotal, saleItems));
        }
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
        publishSaleEvents(businessId, req.branchId(), shift.getId(), saleId, userId, grandTotal, resolved.normalized(), cashIn);
        enqueueSaleCompletedWebhook(businessId, completed, effectiveSoldAt);

        // Publish real-time payment.confirmed for each payment method
        for (NormalizedPayment p : resolved.normalized()) {
            eventPublisher.publishEvent(
                    new zelisline.ub.platform.realtime.RealtimeBridge.PaymentConfirmedEvent(
                            businessId, req.branchId(), saleId, p.amount(),
                            p.method(), userId));
        }

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

    private void publishSaleEvents(String businessId, String branchId, String shiftId, String saleId, String userId,
                                   BigDecimal grandTotal, List<NormalizedPayment> payments, BigDecimal cashIn) {
        auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.SALES, AuditEventTypes.SALE_COMPLETED, AuditEventSeverity.INFO)
                .businessId(businessId)
                .branchId(branchId)
                .actor(userId, AuditEventActorType.USER)
                .target("sale", saleId)
                .targetLabel("Sale " + saleId.substring(0, 8))
                .shiftId(shiftId)
                .newState(map("grandTotal", grandTotal.toPlainString(), "paymentMethods", paymentMethodSummary(payments)))
                .source("pos_terminal")
                .build());

        if (cashIn.signum() > 0) {
            auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.CASH_DRAWER, AuditEventTypes.CASH_SALE_ADDED, AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .branchId(branchId)
                    .actor(userId, AuditEventActorType.USER)
                    .target("shift", shiftId)
                    .targetLabel("Shift " + shiftId.substring(0, 8))
                    .shiftId(shiftId)
                    .newState(map("cashAmount", cashIn.toPlainString()))
                    .source("pos_terminal")
                    .build());
        }

        for (NormalizedPayment p : payments) {
            auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.SALES, AuditEventTypes.PAYMENT_TENDERED, AuditEventSeverity.INFO)
                    .businessId(businessId)
                    .branchId(branchId)
                    .actor(userId, AuditEventActorType.USER)
                    .target("sale", saleId)
                    .targetLabel("Sale " + saleId.substring(0, 8))
                    .shiftId(shiftId)
                    .newState(map("method", p.method(), "amount", p.amount().toPlainString(), "reference", p.reference()))
                    .source("pos_terminal")
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

    private static Map<String, BigDecimal> paymentMethodSummary(List<NormalizedPayment> payments) {
        Map<String, BigDecimal> summary = new LinkedHashMap<>();
        for (NormalizedPayment p : payments) {
            summary.merge(p.method(), p.amount(), BigDecimal::add);
        }
        return summary;
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
        Map<String, BigDecimal> tenderDr = new LinkedHashMap<>();
        for (NormalizedPayment p : paymentsNorm) {
            String code = SalePaymentLedger.ledgerCodeForPaymentMethod(p.method());
            tenderDr.merge(code, p.amount(), BigDecimal::add);
        }

        JournalEntry entry = new JournalEntry();
        entry.setBusinessId(businessId);
        entry.setEntryDate(LocalDate.now(ZoneOffset.UTC));
        entry.setSourceType(SalesConstants.JOURNAL_SOURCE_SALE);
        entry.setSourceId(saleId);
        entry.setMemo("POS sale " + saleId);

        for (Map.Entry<String, BigDecimal> e : tenderDr.entrySet()) {
            BigDecimal amt = e.getValue().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            entry.debit(ledgerAccountResolver.resolveId(businessId, e.getKey()), amt);
        }
        entry.credit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.SALES_REVENUE), grandTotal);
        if (overpayToWallet.signum() > 0) {
            entry.credit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.CUSTOMER_WALLET_LIABILITY), overpayToWallet);
        }
        entry.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.COST_OF_GOODS_SOLD), cogs);
        entry.credit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.INVENTORY), cogs);

        return ledgerPostingPort.post(entry);
    }

    private SaleResponse toResponse(Sale sale) {
        List<SaleItem> items = saleItemRepository.findBySaleIdOrderByLineIndexAsc(sale.getId());
        List<SalePayment> pays = salePaymentRepository.findBySaleIdOrderBySortOrderAsc(sale.getId());
        return SaleResponseMapper.map(
                sale,
                items,
                pays,
                saleActorNameService.resolveSoldByName(sale.getBusinessId(), sale.getSoldBy())
        );
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

    private void validateSaleLines(String businessId, String branchId, List<PostSaleLineRequest> lines, String roleId) {
        if (lines == null || lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sale must contain at least one line");
        }
        List<String> itemIds = lines.stream().map(PostSaleLineRequest::itemId).distinct().toList();
        Map<String, zelisline.ub.catalog.domain.Item> itemsById = itemRepository
                .findByIdInAndBusinessIdAndDeletedAtIsNull(itemIds, businessId)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        zelisline.ub.catalog.domain.Item::getId,
                        item -> item,
                        (a, b) -> a));

        Map<String, BigDecimal> shelfPrices = pricingService.getCurrentOpenSellingPricesForItems(
                businessId, branchId, itemIds);

        for (int i = 0; i < lines.size(); i++) {
            PostSaleLineRequest line = lines.get(i);
            zelisline.ub.catalog.domain.Item item = itemsById.get(line.itemId());
            if (item == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Line " + (i + 1) + ": item not found");
            }
            if (line.quantity() == null || line.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Line " + (i + 1) + ": quantity must be positive");
            }
            if (line.unitPrice() == null || line.unitPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Line " + (i + 1) + ": unit price must be positive");
            }

            if (item.isWeighed()) {
                String unit = item.getUnitType() == null ? "each" : item.getUnitType().trim().toLowerCase(Locale.ROOT);
                if (!WEIGHT_UNITS.contains(unit)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Line " + (i + 1) + ": weighed item must use a weight unit (kg, g, lb)");
                }
                // v1: kg only for weighed sale lines to avoid unit-conversion complexity.
                if (!"kg".equals(unit)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Line " + (i + 1) + ": weighed items currently sell in kg only");
                }
                if (line.quantity().scale() > WEIGHTED_QTY_SCALE) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Line " + (i + 1) + ": weight may have at most " + WEIGHTED_QTY_SCALE + " decimal places");
                }
            } else {
                // Non-weighed items must be sold with integer quantities.
                BigDecimal stripped = line.quantity().stripTrailingZeros();
                if (stripped.scale() > 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Line " + (i + 1) + ": non-weighed items must have a whole-number quantity");
                }
            }

            BigDecimal shelfPrice = shelfPrices.get(line.itemId());
            if (shelfPrice != null && isPriceOverride(line.unitPrice(), shelfPrice)
                    && !hasPriceOverridePermission(roleId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Line " + (i + 1) + ": price override requires manager approval");
            }
        }
    }

    private boolean isPriceOverride(BigDecimal unitPrice, BigDecimal shelfPrice) {
        return unitPrice.subtract(shelfPrice).abs().compareTo(TOLERANCE) > 0;
    }

    private boolean hasPriceOverridePermission(String roleId) {
        return roleId != null && requestPermissionService.hasPermission(roleId, PRICE_OVERRIDE_PERMISSION);
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
                // Reference is optional for mpesa_manual — STK Push auto-sets it
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

    private CreditSaleReminderEvent buildCreditSaleReminderEvent(
            String businessId,
            String saleId,
            String customerId,
            BigDecimal creditTenderTotal,
            List<SaleItem> saleItems
    ) {
        List<CreditSaleReminderLineItem> items = buildReminderLineItems(businessId, saleItems);
        BigDecimal balanceOwed = creditAccountRepository
                .findByCustomerIdAndBusinessId(customerId, businessId)
                .map(acc -> acc.getBalanceOwed())
                .orElse(BigDecimal.ZERO);
        return new CreditSaleReminderEvent(
                businessId,
                saleId,
                customerId,
                creditTenderTotal,
                countCreditSaleItems(saleItems),
                items,
                balanceOwed);
    }

    private List<CreditSaleReminderLineItem> buildReminderLineItems(String businessId, List<SaleItem> saleItems) {
        if (saleItems == null || saleItems.isEmpty()) {
            return List.of();
        }
        List<String> itemIds = saleItems.stream()
                .map(SaleItem::getItemId)
                .distinct()
                .toList();
        Map<String, String> itemNames = itemRepository
                .findByIdInAndBusinessIdAndDeletedAtIsNull(itemIds, businessId)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        zelisline.ub.catalog.domain.Item::getId,
                        item -> item.getName() != null ? item.getName() : "Item",
                        (a, b) -> a));
        return saleItems.stream()
                .map(line -> new CreditSaleReminderLineItem(
                        itemNames.getOrDefault(line.getItemId(), "Item"),
                        line.getQuantity(),
                        line.getLineTotal()))
                .toList();
    }

    private static int countCreditSaleItems(List<SaleItem> items) {
        int total = 0;
        for (SaleItem line : items) {
            BigDecimal q = line.getQuantity();
            if (q != null && q.signum() > 0) {
                total += q.setScale(0, RoundingMode.HALF_UP).intValue();
            }
        }
        if (total > 0) {
            return total;
        }
        return Math.max(1, items.size());
    }
}
