package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.PatchSupplyBatchRequest;
import zelisline.ub.inventory.api.dto.PostSupplyBatchExpenseRequest;
import zelisline.ub.inventory.api.dto.SupplyBatchDetailResponse;
import zelisline.ub.inventory.api.dto.SupplyBatchExpenseResponse;
import zelisline.ub.inventory.api.dto.SupplyBatchItemResponse;
import zelisline.ub.inventory.api.dto.SupplyBatchSummaryResponse;
import zelisline.ub.inventory.domain.SupplyBatch;
import zelisline.ub.inventory.domain.SupplyBatchExpense;
import zelisline.ub.inventory.repository.SupplyBatchExpenseRepository;
import zelisline.ub.inventory.repository.SupplyBatchRepository;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class SupplyBatchReportService {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final int QTY_SCALE = 4;

    private final SupplyBatchRepository supplyBatchRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final SaleItemRepository saleItemRepository;
    private final SupplyBatchExpenseRepository supplyBatchExpenseRepository;

    public List<SupplyBatchSummaryResponse> listSupplyBatches(
            String businessId,
            String branchId,
            String supplierId,
            String status
    ) {
        String effectiveStatus = (status != null && !status.isBlank()) ? status : null;
        List<SupplyBatch> batches = supplyBatchRepository.findByFilters(
                businessId, branchId, supplierId, effectiveStatus);
        return batches.stream()
                .map(b -> toSummary(businessId, b))
                .toList();
    }

    public SupplyBatchDetailResponse getSupplyBatchDetail(String businessId, String batchId) {
        SupplyBatch sb = supplyBatchRepository.findByIdAndBusinessId(batchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supply batch not found"));

        List<InventoryBatch> lines = inventoryBatchRepository.findBySupplyBatchId(batchId);

        List<SupplyBatchItemResponse> items = lines.stream()
                .map(this::toItemResponse)
                .toList();

        String supplierName = resolveSupplierName(businessId, sb.getSupplierId());

        // ── Compute financials ──────────────────────────────────────
        BigDecimal totalCost = computeTotalCost(lines);
        BigDecimal totalRevenue = supplyBatchRepository.sumRevenueBySupplyBatchId(batchId, businessId);
        List<SupplyBatchExpense> expenses = supplyBatchExpenseRepository.findBySupplyBatchIdOrderByCreatedAtAsc(batchId);
        BigDecimal totalAssociatedCosts = expenses.stream()
                .map(SupplyBatchExpense::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int soldPercentage = computeSoldPercentage(lines);

        List<SupplyBatchExpenseResponse> expenseResponses = expenses.stream()
                .map(e -> new SupplyBatchExpenseResponse(
                        e.getId(), e.getSupplyBatchId(), e.getCategory(),
                        e.getAmount(), e.getDescription(), e.getCreatedAt(), e.getCreatedBy()))
                .toList();

        return new SupplyBatchDetailResponse(
                sb.getId(),
                sb.getBatchNumber(),
                sb.getBatchName(),
                sb.getSupplierId(),
                supplierName,
                sb.getBranchId(),
                sb.getReceivedAt(),
                sb.getStatus(),
                sb.getSourceType(),
                sb.getItemCount(),
                sb.getTotalInitialQuantity(),
                sb.getTotalRemainingQuantity(),
                sb.getClosedAt(),
                sb.getClosedBy(),
                items,
                totalCost,
                totalRevenue,
                totalAssociatedCosts,
                soldPercentage,
                expenseResponses
        );
    }

    private SupplyBatchSummaryResponse toSummary(String businessId, SupplyBatch sb) {
        String supplierName = resolveSupplierName(businessId, sb.getSupplierId());

        // ── Compute financials ──────────────────────────────────────
        List<InventoryBatch> lines = inventoryBatchRepository.findBySupplyBatchId(sb.getId());
        BigDecimal totalCost = computeTotalCost(lines);
        BigDecimal totalRevenue = supplyBatchRepository.sumRevenueBySupplyBatchId(sb.getId(), businessId);
        BigDecimal totalAssociatedCosts = supplyBatchExpenseRepository.sumBySupplyBatchId(sb.getId(), businessId);
        int soldPercentage = computeSoldPercentage(lines);

        return new SupplyBatchSummaryResponse(
                sb.getId(),
                sb.getBatchNumber(),
                sb.getBatchName(),
                sb.getSupplierId(),
                supplierName,
                sb.getBranchId(),
                sb.getReceivedAt(),
                sb.getStatus(),
                sb.getItemCount(),
                sb.getTotalInitialQuantity(),
                sb.getTotalRemainingQuantity(),
                sb.getClosedAt(),
                sb.getClosedBy(),
                totalCost,
                totalRevenue,
                totalAssociatedCosts,
                soldPercentage
        );
    }

    // ── Financial helpers ─────────────────────────────────────────

    private BigDecimal computeTotalCost(List<InventoryBatch> lines) {
        return lines.stream()
                .map(ib -> ib.getInitialQuantity().multiply(ib.getUnitCost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private int computeSoldPercentage(List<InventoryBatch> lines) {
        BigDecimal totalInitial = lines.stream()
                .map(InventoryBatch::getInitialQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRemaining = lines.stream()
                .map(InventoryBatch::getQuantityRemaining)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalInitial.signum() <= 0) return 0;
        BigDecimal sold = totalInitial.subtract(totalRemaining).max(BigDecimal.ZERO);
        return sold.multiply(ONE_HUNDRED)
                .divide(totalInitial, 0, RoundingMode.HALF_UP)
                .intValue();
    }

    private String resolveSupplierName(String businessId, String supplierId) {
        if (supplierId == null) {
            return null;
        }
        return supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
                .map(Supplier::getName)
                .orElse(null);
    }

    private SupplyBatchItemResponse toItemResponse(InventoryBatch ib) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(ib.getItemId(), ib.getBusinessId())
                .orElse(null);
        String itemName = item != null ? item.getName() : null;
        String itemSku = item != null ? item.getSku() : null;

        BigDecimal sold = calculateSold(ib.getId());
        BigDecimal wasted = calculateWasted(ib.getId());

        return new SupplyBatchItemResponse(
                ib.getId(),
                ib.getItemId(),
                itemName,
                itemSku,
                ib.getBatchNumber(),
                ib.getInitialQuantity(),
                ib.getQuantityRemaining(),
                sold,
                wasted,
                ib.getUnitCost(),
                ib.getExpiryDate() != null ? ib.getExpiryDate().toString() : null,
                ib.getStatus()
        );
    }

    private BigDecimal calculateSold(String inventoryBatchId) {
        List<SaleItem> saleItems = saleItemRepository.findByBatchId(inventoryBatchId);
        return saleItems.stream()
                .map(SaleItem::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal calculateWasted(String inventoryBatchId) {
        List<StockMovement> moves = stockMovementRepository.findByBatchIdAndMovementType(
                inventoryBatchId, PurchasingConstants.MOVEMENT_WASTAGE);
        List<StockMovement> clearanceMoves = stockMovementRepository.findByBatchIdAndMovementType(
                inventoryBatchId, InventoryConstants.MOVEMENT_BATCH_CLEARANCE);
        BigDecimal wasteTotal = moves.stream()
                .map(m -> m.getQuantityDelta().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal clearanceTotal = clearanceMoves.stream()
                .map(m -> m.getQuantityDelta().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return wasteTotal.add(clearanceTotal);
    }

    @Transactional
    public SupplyBatchExpenseResponse addExpense(
            String businessId, String batchId,
            PostSupplyBatchExpenseRequest req, String userId
    ) {
        SupplyBatch sb = supplyBatchRepository.findByIdAndBusinessId(batchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supply batch not found"));
        if (InventoryConstants.SUPPLY_BATCH_STATUS_CLOSED.equals(sb.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot add expenses to a closed batch.");
        }
        SupplyBatchExpense e = new SupplyBatchExpense();
        e.setBusinessId(businessId);
        e.setSupplyBatchId(batchId);
        e.setCategory(req.category().trim().toLowerCase());
        e.setAmount(req.amount().setScale(2, RoundingMode.HALF_UP));
        e.setDescription(req.description() != null ? req.description().trim() : null);
        e.setCreatedBy(userId);
        supplyBatchExpenseRepository.save(e);
        return new SupplyBatchExpenseResponse(
                e.getId(), e.getSupplyBatchId(), e.getCategory(),
                e.getAmount(), e.getDescription(), e.getCreatedAt(), e.getCreatedBy()
        );
    }

    @Transactional
    public void deleteExpense(String businessId, String batchId, String expenseId) {
        SupplyBatch sb = supplyBatchRepository.findByIdAndBusinessId(batchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supply batch not found"));
        if (InventoryConstants.SUPPLY_BATCH_STATUS_CLOSED.equals(sb.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot delete expenses from a closed batch.");
        }
        SupplyBatchExpense e = supplyBatchExpenseRepository.findById(expenseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found"));
        if (!e.getSupplyBatchId().equals(batchId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expense does not belong to this batch.");
        }
        supplyBatchExpenseRepository.delete(e);
    }

    /**
     * Updates editable fields on a SupplyBatch.
     */
    public void patchSupplyBatch(String businessId, String batchId, PatchSupplyBatchRequest req) {
        SupplyBatch sb = supplyBatchRepository.findByIdAndBusinessId(batchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supply batch not found"));
        if (InventoryConstants.SUPPLY_BATCH_STATUS_CLOSED.equals(sb.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Closed batches cannot be edited.");
        }
        if (req.batchName() != null) {
            sb.setBatchName(req.batchName().trim().isEmpty() ? null : req.batchName().trim());
        }
        if (req.status() != null && !req.status().isBlank()) {
            sb.setStatus(req.status().trim().toLowerCase());
        }
        supplyBatchRepository.save(sb);
    }

    /**
     * Recalculates and updates the aggregate totals on a SupplyBatch
     * from its linked InventoryBatch rows.
     */
    public void recalculateTotals(String businessId, String batchId) {
        SupplyBatch sb = supplyBatchRepository.findByIdAndBusinessId(batchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supply batch not found"));
        List<InventoryBatch> lines = inventoryBatchRepository.findBySupplyBatchId(batchId);
        int itemCount = lines.size();
        BigDecimal totalInitial = lines.stream()
                .map(InventoryBatch::getInitialQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRemaining = lines.stream()
                .map(InventoryBatch::getQuantityRemaining)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        sb.setItemCount(itemCount);
        sb.setTotalInitialQuantity(totalInitial);
        sb.setTotalRemainingQuantity(totalRemaining);
        supplyBatchRepository.save(sb);
    }
}
