package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditSettingsService;
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
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.sales.SalePaymentLedger;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.PostVoidSaleRequest;
import zelisline.ub.sales.api.dto.SaleResponse;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.domain.SalePayment;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SalePaymentRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;

@Service
@RequiredArgsConstructor
public class SaleVoidService {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final int MONEY_SCALE = 2;
    private static final int QTY_SCALE = 4;
    private static final String PERM_VOID_ANY = "sales.void.any";
    private static final String PERM_VOID_OWN = "sales.void.own";

    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final SalePaymentRepository salePaymentRepository;
    private final ShiftRepository shiftRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ItemRepository itemRepository;
    private final LedgerBootstrapService ledgerBootstrapService;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;
    private final RequestPermissionService requestPermissionService;
    private final CreditSaleDebtService creditSaleDebtService;
    private final WalletLedgerService walletLedgerService;
    private final LoyaltyPointsService loyaltyPointsService;
    private final BusinessCreditSettingsService businessCreditSettingsService;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public SaleResponse voidSale(
            String businessId,
            String saleId,
            String userId,
            String roleId,
            PostVoidSaleRequest req
    ) {
        Sale sale = saleRepository.findByIdAndBusinessId(saleId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale not found"));
        if (SalesConstants.SALE_STATUS_VOIDED.equals(sale.getStatus())) {
            return responseFor(sale);
        }
        if (sale.getRefundedTotal() != null && sale.getRefundedTotal().signum() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot void a sale that has refunds");
        }
        if (!SalesConstants.SALE_STATUS_COMPLETED.equals(sale.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only completed sales can be voided");
        }
        assertCanVoid(sale, userId, roleId);

        Shift shift = shiftRepository
                .findByBusinessIdAndBranchIdAndStatusForUpdate(businessId, sale.getBranchId(), SalesConstants.SHIFT_STATUS_OPEN)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No open shift for this branch"));
        if (!shift.getId().equals(sale.getShiftId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Sale can only be voided on the same open shift that recorded it"
            );
        }

        List<SaleItem> itemRows = saleItemRepository.findBySaleIdOrderByLineIndexAsc(saleId);
        List<SalePayment> payments = salePaymentRepository.findBySaleIdOrderBySortOrderAsc(saleId);
        restoreInventory(businessId, sale, itemRows, userId);

        BigDecimal cashBack = sumCashPayments(payments);
        if (cashBack.signum() > 0) {
            BigDecimal next = shift.getExpectedClosingCash()
                    .subtract(cashBack)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            if (next.signum() < 0) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Voiding would make expected drawer cash negative; reconcile the drawer first"
                );
            }
            shift.setExpectedClosingCash(next);
            shiftRepository.save(shift);
        }

        BigDecimal grandTotal = sale.getGrandTotal().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal cogs = sumItemCosts(itemRows);
        BigDecimal tenderSum = sumPaymentAmounts(payments);
        BigDecimal overpay = tenderSum.subtract(grandTotal).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (overpay.signum() > 0 && (sale.getCustomerId() == null || sale.getCustomerId().isBlank())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sale with wallet overpay missing customer linkage");
        }
        String voidJeId = postVoidJournal(businessId, saleId, grandTotal, cogs, payments, overpay);
        creditSaleDebtService.reverseDebtForVoidedSale(businessId, sale, payments);
        walletLedgerService.reverseWalletEffectsForVoidedSale(businessId, saleId, sale.getCustomerId());
        loyaltyPointsService.reverseLoyaltyForVoidedSale(
                businessId,
                saleId,
                sale.getCustomerId(),
                businessCreditSettingsService.resolveForBusiness(businessId));

        sale.setStatus(SalesConstants.SALE_STATUS_VOIDED);
        sale.setVoidedAt(Instant.now());
        sale.setVoidedBy(userId);
        sale.setVoidJournalEntryId(voidJeId);
        sale.setVoidNotes(blankToNull(req != null ? req.notes() : null));
        saleRepository.save(sale);
        return responseFor(sale);
    }

    private void assertCanVoid(Sale sale, String userId, String roleId) {
        boolean any = requestPermissionService.hasPermission(roleId, PERM_VOID_ANY);
        boolean own = requestPermissionService.hasPermission(roleId, PERM_VOID_OWN);
        if (!any && !own) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing void permission");
        }
        if (!any && own && !sale.getSoldBy().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Can only void your own sales");
        }
    }

    private SaleResponse responseFor(Sale sale) {
        List<SaleItem> items = saleItemRepository.findBySaleIdOrderByLineIndexAsc(sale.getId());
        List<SalePayment> pays = salePaymentRepository.findBySaleIdOrderBySortOrderAsc(sale.getId());
        return SaleResponseMapper.map(sale, items, pays);
    }

    private void restoreInventory(String businessId, Sale sale, List<SaleItem> itemRows, String userId) {
        List<SaleItem> sorted = new ArrayList<>(itemRows);
        sorted.sort(Comparator.comparing(SaleItem::getItemId).thenComparing(SaleItem::getBatchId));
        Item item = null;
        String lastItemId = null;
        for (SaleItem si : sorted) {
            if (!si.getItemId().equals(lastItemId)) {
                if (item != null) {
                    itemRepository.save(item);
                }
                item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(si.getItemId(), businessId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
                entityManager.lock(item, LockModeType.PESSIMISTIC_WRITE);
                lastItemId = si.getItemId();
            }
            InventoryBatch b = inventoryBatchRepository
                    .findByIdAndBusinessIdForUpdate(si.getBatchId(), businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Batch not found"));
            if (!b.getBranchId().equals(sale.getBranchId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Batch branch mismatch");
            }
            BigDecimal q = si.getQuantity().setScale(QTY_SCALE, RoundingMode.HALF_UP);
            BigDecimal nextQty = b.getQuantityRemaining().add(q).setScale(QTY_SCALE, RoundingMode.HALF_UP);
            b.setQuantityRemaining(nextQty);
            inventoryBatchRepository.save(b);

            StockMovement sm = new StockMovement();
            sm.setBusinessId(businessId);
            sm.setBranchId(sale.getBranchId());
            sm.setItemId(si.getItemId());
            sm.setBatchId(si.getBatchId());
            sm.setMovementType(InventoryConstants.MOVEMENT_SALE_VOID);
            sm.setReferenceType(SalesConstants.STOCK_REFERENCE_TYPE_SALE_VOID);
            sm.setReferenceId(sale.getId());
            sm.setQuantityDelta(q);
            sm.setUnitCost(si.getUnitCost());
            sm.setNotes("Sale void");
            sm.setCreatedBy(userId);
            stockMovementRepository.save(sm);
            applyStockDelta(item, q);
        }
        if (item != null) {
            itemRepository.save(item);
        }
    }

    private static void applyStockDelta(Item item, BigDecimal delta) {
        BigDecimal base = item.getCurrentStock() == null ? BigDecimal.ZERO : item.getCurrentStock();
        BigDecimal next = base.add(delta).setScale(QTY_SCALE, RoundingMode.HALF_UP);
        item.setCurrentStock(next);
    }

    private static BigDecimal sumPaymentAmounts(List<SalePayment> payments) {
        BigDecimal s = BigDecimal.ZERO;
        for (SalePayment p : payments) {
            s = s.add(p.getAmount());
        }
        return s.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal sumCashPayments(List<SalePayment> payments) {
        BigDecimal s = BigDecimal.ZERO;
        for (SalePayment p : payments) {
            if (SalesConstants.PAYMENT_METHOD_CASH.equals(p.getMethod())) {
                s = s.add(p.getAmount());
            }
        }
        return s.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal sumItemCosts(List<SaleItem> items) {
        BigDecimal t = BigDecimal.ZERO;
        for (SaleItem si : items) {
            t = t.add(si.getCostTotal());
        }
        return t.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private String postVoidJournal(
            String businessId,
            String saleId,
            BigDecimal grandTotal,
            BigDecimal cogs,
            List<SalePayment> payments,
            BigDecimal saleOverpaymentToWallet
    ) {
        ledgerBootstrapService.ensureStandardAccounts(businessId);
        LedgerAccount revenue = ledger(businessId, LedgerAccountCodes.SALES_REVENUE);
        LedgerAccount cogsAcc = ledger(businessId, LedgerAccountCodes.COST_OF_GOODS_SOLD);
        LedgerAccount inv = ledger(businessId, LedgerAccountCodes.INVENTORY);

        Map<String, BigDecimal> tenderCr = new LinkedHashMap<>();
        for (SalePayment p : payments) {
            String code = SalePaymentLedger.ledgerCodeForPaymentMethod(p.getMethod());
            tenderCr.merge(code, p.getAmount(), BigDecimal::add);
        }

        JournalEntry je = new JournalEntry();
        je.setBusinessId(businessId);
        je.setEntryDate(LocalDate.now(ZoneOffset.UTC));
        je.setSourceType(SalesConstants.JOURNAL_SOURCE_SALE_VOID);
        je.setSourceId(saleId);
        je.setMemo("Void sale " + saleId);
        journalEntryRepository.save(je);

        grandTotal = grandTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        cogs = cogs.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal overpayWallet = saleOverpaymentToWallet.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        List<JournalLine> lines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : tenderCr.entrySet()) {
            BigDecimal amt = e.getValue().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            lines.add(journalCredit(je.getId(), ledger(businessId, e.getKey()).getId(), amt));
        }
        lines.add(journalDebit(je.getId(), revenue.getId(), grandTotal));
        if (overpayWallet.signum() > 0) {
            LedgerAccount wl = ledger(businessId, LedgerAccountCodes.CUSTOMER_WALLET_LIABILITY);
            lines.add(journalDebit(je.getId(), wl.getId(), overpayWallet));
        }
        lines.add(journalCredit(je.getId(), cogsAcc.getId(), cogs));
        lines.add(journalDebit(je.getId(), inv.getId(), cogs));
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
            throw new IllegalStateException("Unbalanced void journal");
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

    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }
}
