package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
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
import zelisline.ub.inventory.api.dto.InventoryMutationResponse;
import zelisline.ub.inventory.api.dto.PostBatchDecreaseRequest;
import zelisline.ub.inventory.api.dto.PostOpeningBalanceRequest;
import zelisline.ub.inventory.api.dto.PostStandaloneWastageRequest;
import zelisline.ub.inventory.api.dto.PostStockIncreaseRequest;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class InventoryLedgerService {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final int QTY_SCALE = 4;

    private final LedgerBootstrapService ledgerBootstrapService;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ItemRepository itemRepository;
    private final BranchRepository branchRepository;

    @Transactional
    public InventoryMutationResponse recordOpeningBalance(
            String businessId,
            PostOpeningBalanceRequest req,
            String userId
    ) {
        ledgerBootstrapService.ensureStandardAccounts(businessId);
        requireBranch(businessId, req.branchId());
        Item item = requireStockedItem(businessId, req.itemId());
        String opId = UUID.randomUUID().toString();
        InventoryBatch batch = saveInboundBatch(
                businessId,
                req.branchId(),
                item.getId(),
                InventoryConstants.BATCH_SOURCE_OPENING,
                opId,
                req.quantity(),
                req.unitCost(),
                opId
        );
        StockMovement mv = persistMovement(
                businessId,
                req.branchId(),
                item.getId(),
                batch.getId(),
                InventoryConstants.MOVEMENT_OPENING,
                opId,
                req.quantity(),
                req.unitCost(),
                req.notes(),
                userId
        );
        applyStockDelta(item, req.quantity());
        BigDecimal value = extensionMoney(req.quantity(), req.unitCost());
        String jeId = saveJournal(
                businessId,
                InventoryConstants.JOURNAL_OPENING,
                opId,
                "Opening balance",
                value,
                true
        );
        return new InventoryMutationResponse(jeId, mv.getId(), batch.getId());
    }

    @Transactional
    public InventoryMutationResponse recordStockIncrease(
            String businessId,
            PostStockIncreaseRequest req,
            String userId
    ) {
        ledgerBootstrapService.ensureStandardAccounts(businessId);
        requireBranch(businessId, req.branchId());
        Item item = requireStockedItem(businessId, req.itemId());
        String opId = UUID.randomUUID().toString();
        InventoryBatch batch = saveInboundBatch(
                businessId,
                req.branchId(),
                item.getId(),
                InventoryConstants.BATCH_SOURCE_STOCK_GAIN,
                opId,
                req.quantity(),
                req.unitCost(),
                opId
        );
        StockMovement mv = persistMovement(
                businessId,
                req.branchId(),
                item.getId(),
                batch.getId(),
                InventoryConstants.MOVEMENT_ADJUSTMENT,
                opId,
                req.quantity(),
                req.unitCost(),
                req.notes(),
                userId
        );
        applyStockDelta(item, req.quantity());
        BigDecimal value = extensionMoney(req.quantity(), req.unitCost());
        String jeId = saveJournal(
                businessId,
                InventoryConstants.JOURNAL_COUNT_GAIN,
                opId,
                "Stock count / gain",
                value,
                true
        );
        return new InventoryMutationResponse(jeId, mv.getId(), batch.getId());
    }

    @Transactional
    public InventoryMutationResponse recordBatchDecrease(
            String businessId,
            PostBatchDecreaseRequest req,
            String userId
    ) {
        ledgerBootstrapService.ensureStandardAccounts(businessId);
        InventoryBatch batch = inventoryBatchRepository.findByIdAndBusinessId(req.batchId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found"));
        if (!InventoryConstants.BATCH_STATUS_ACTIVE.equals(batch.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Batch is not active");
        }
        if (batch.getQuantityRemaining().compareTo(req.quantity()) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity exceeds batch remaining");
        }
        Item item = requireStockedItem(businessId, batch.getItemId());
        String opId = UUID.randomUUID().toString();
        batch.setQuantityRemaining(batch.getQuantityRemaining().subtract(req.quantity()));
        inventoryBatchRepository.save(batch);

        StockMovement mv = persistMovement(
                businessId,
                batch.getBranchId(),
                item.getId(),
                batch.getId(),
                InventoryConstants.MOVEMENT_ADJUSTMENT,
                opId,
                req.quantity().negate(),
                batch.getUnitCost(),
                req.reason(),
                userId
        );
        applyStockDelta(item, req.quantity().negate());
        BigDecimal value = extensionMoney(req.quantity(), batch.getUnitCost());
        String jeId = saveJournal(
                businessId,
                InventoryConstants.JOURNAL_ADJUSTMENT_DOWN,
                opId,
                "Inventory adjustment (decrease)",
                value,
                false
        );
        return new InventoryMutationResponse(jeId, mv.getId(), batch.getId());
    }

    @Transactional
    public InventoryMutationResponse recordStandaloneWastage(
            String businessId,
            PostStandaloneWastageRequest req,
            String userId
    ) {
        ledgerBootstrapService.ensureStandardAccounts(businessId);
        requireBranch(businessId, req.branchId());
        Item item = requireStockedItem(businessId, req.itemId());
        String opId = UUID.randomUUID().toString();
        StockMovement mv = persistMovement(
                businessId,
                req.branchId(),
                item.getId(),
                null,
                PurchasingConstants.MOVEMENT_WASTAGE,
                opId,
                req.quantity().negate(),
                req.unitCost(),
                req.reason(),
                userId
        );
        applyStockDelta(item, req.quantity().negate());
        BigDecimal value = extensionMoney(req.quantity(), req.unitCost());
        String jeId = saveJournal(
                businessId,
                InventoryConstants.JOURNAL_STANDALONE_WASTAGE,
                opId,
                "Inventory wastage",
                value,
                false
        );
        return new InventoryMutationResponse(jeId, mv.getId(), null);
    }

    private InventoryBatch saveInboundBatch(
            String businessId,
            String branchId,
            String itemId,
            String sourceType,
            String sourceId,
            BigDecimal quantity,
            BigDecimal unitCost,
            String operationId
    ) {
        InventoryBatch b = new InventoryBatch();
        b.setBusinessId(businessId);
        b.setBranchId(branchId);
        b.setItemId(itemId);
        b.setSupplierId(null);
        b.setBatchNumber("P3-" + operationId.substring(0, 8).toUpperCase());
        b.setSourceType(sourceType);
        b.setSourceId(sourceId);
        b.setInitialQuantity(quantity);
        b.setQuantityRemaining(quantity);
        b.setUnitCost(unitCost.setScale(QTY_SCALE, RoundingMode.HALF_UP));
        b.setReceivedAt(Instant.now());
        inventoryBatchRepository.save(b);
        return b;
    }

    private StockMovement persistMovement(
            String businessId,
            String branchId,
            String itemId,
            String batchId,
            String movementType,
            String operationId,
            BigDecimal quantityDelta,
            BigDecimal unitCost,
            String notes,
            String userId
    ) {
        StockMovement sm = new StockMovement();
        sm.setBusinessId(businessId);
        sm.setBranchId(branchId);
        sm.setItemId(itemId);
        sm.setBatchId(batchId);
        sm.setMovementType(movementType);
        sm.setReferenceType(InventoryConstants.REF_OPERATION);
        sm.setReferenceId(operationId);
        sm.setQuantityDelta(quantityDelta);
        sm.setUnitCost(unitCost);
        sm.setNotes(notes);
        sm.setCreatedBy(userId);
        stockMovementRepository.save(sm);
        return sm;
    }

    /** When increase: Dr inventory, Cr equity. When decrease class: Dr shrinkage, Cr inventory. */
    private String saveJournal(
            String businessId,
            String sourceType,
            String sourceId,
            String memo,
            BigDecimal extensionValue,
            boolean isInboundInventory
    ) {
        if (extensionValue.signum() <= 0) {
            throw new IllegalStateException("Journal amount must be positive");
        }
        LedgerAccount inv = ledger(businessId, LedgerAccountCodes.INVENTORY);
        JournalEntry je = new JournalEntry();
        je.setBusinessId(businessId);
        je.setEntryDate(LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC));
        je.setSourceType(sourceType);
        je.setSourceId(sourceId);
        je.setMemo(memo);
        journalEntryRepository.save(je);

        List<JournalLine> lines = new ArrayList<>(2);
        BigDecimal v = extensionValue.setScale(2, RoundingMode.HALF_UP);
        if (isInboundInventory) {
            LedgerAccount obe = ledger(businessId, LedgerAccountCodes.OPENING_BALANCE_EQUITY);
            lines.add(lineDebit(je.getId(), inv.getId(), v));
            lines.add(lineCredit(je.getId(), obe.getId(), v));
        } else {
            LedgerAccount shrink = ledger(businessId, LedgerAccountCodes.INVENTORY_SHRINKAGE);
            lines.add(lineDebit(je.getId(), shrink.getId(), v));
            lines.add(lineCredit(je.getId(), inv.getId(), v));
        }
        journalLineRepository.saveAll(lines);
        assertBalanced(lines);
        return je.getId();
    }

    private void assertBalanced(List<JournalLine> lines) {
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

    private static JournalLine lineDebit(String entryId, String accId, BigDecimal amount) {
        JournalLine l = new JournalLine();
        l.setJournalEntryId(entryId);
        l.setLedgerAccountId(accId);
        l.setDebit(amount);
        l.setCredit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        return l;
    }

    private static JournalLine lineCredit(String entryId, String accId, BigDecimal amount) {
        JournalLine l = new JournalLine();
        l.setJournalEntryId(entryId);
        l.setLedgerAccountId(accId);
        l.setDebit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        l.setCredit(amount);
        return l;
    }

    private LedgerAccount ledger(String businessId, String code) {
        return ledgerAccountRepository.findByBusinessIdAndCode(businessId, code)
                .orElseThrow(() -> new IllegalStateException("Missing ledger account " + code));
    }

    private void requireBranch(String businessId, String branchId) {
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
    }

    private Item requireStockedItem(String businessId, String itemId) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
        if (!item.isStocked()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item is not stocked");
        }
        return item;
    }

    private void applyStockDelta(Item item, BigDecimal delta) {
        BigDecimal base = item.getCurrentStock() == null ? BigDecimal.ZERO : item.getCurrentStock();
        BigDecimal next = base.add(delta).setScale(QTY_SCALE, RoundingMode.HALF_UP);
        if (next.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock cannot go negative");
        }
        item.setCurrentStock(next);
        itemRepository.save(item);
    }

    private static BigDecimal extensionMoney(BigDecimal qty, BigDecimal unitCost) {
        return qty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
    }
}
