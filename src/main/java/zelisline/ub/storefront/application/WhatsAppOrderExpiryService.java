package zelisline.ub.storefront.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.application.InventoryBatchPickerService;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.storefront.WebOrderChannels;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.domain.WebOrderLine;
import zelisline.ub.storefront.repository.WebOrderLineRepository;
import zelisline.ub.storefront.repository.WebOrderRepository;

/**
 * WhatsApp order expiry (scope §11). Unconfirmed WhatsApp orders hold stock for
 * {@code whatsappOrderExpiryMins}; the sweeper then marks them expired and
 * reverses the sale movements — never a raw decrement. Expiry is soft: the
 * order stays visible and confirming it re-decrements stock.
 */
@Service
@RequiredArgsConstructor
public class WhatsAppOrderExpiryService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppOrderExpiryService.class);

    private static final int QTY_SCALE = 4;

    private final WebOrderRepository webOrderRepository;
    private final WebOrderLineRepository webOrderLineRepository;
    private final StockMovementRepository stockMovementRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final ItemRepository itemRepository;
    private final PackageVariantStockResolver packageVariantStockResolver;
    private final InventoryBatchPickerService inventoryBatchPickerService;

    @Transactional
    public int sweepExpired() {
        Instant now = Instant.now();
        List<WebOrder> expired = webOrderRepository.findExpiredUnconfirmedWhatsAppOrders(now);
        int released = 0;
        for (WebOrder order : expired) {
            try {
                order.setHandoffState("expired");
                webOrderRepository.save(order);
                releaseStock(order);
                released++;
            } catch (Exception e) {
                log.warn("Failed to expire WhatsApp order {}: {}", order.getId(), e.getMessage());
            }
        }
        return released;
    }

    /**
     * Reverse the {@code MOVEMENT_SALE} stock movements recorded at checkout —
     * the batch-level inverse of {@code pickAndApplyPhysicalDecrement}.
     */
    @Transactional
    public void releaseStock(WebOrder order) {
        List<StockMovement> movements = stockMovementRepository
                .findByBusinessIdAndReferenceTypeAndReferenceId(
                        order.getBusinessId(),
                        SalesConstants.STOCK_REFERENCE_TYPE_WEB_ORDER,
                        order.getId())
                .stream()
                .filter(m -> InventoryConstants.MOVEMENT_SALE.equals(m.getMovementType()))
                .toList();
        for (StockMovement movement : movements) {
            BigDecimal qtyBack = movement.getQuantityDelta().abs();
            Item item = itemRepository
                    .findByIdAndBusinessIdAndDeletedAtIsNull(movement.getItemId(), order.getBusinessId())
                    .orElse(null);
            InventoryBatch batch = inventoryBatchRepository.findById(movement.getBatchId()).orElse(null);
            if (item != null) {
                item.setCurrentStock(round(item.getCurrentStock().add(qtyBack)));
                itemRepository.save(item);
            }
            if (batch != null) {
                batch.setQuantityRemaining(round(batch.getQuantityRemaining().add(qtyBack)));
                inventoryBatchRepository.save(batch);
            }
            StockMovement release = new StockMovement();
            release.setBusinessId(order.getBusinessId());
            release.setBranchId(order.getCatalogBranchId());
            release.setItemId(movement.getItemId());
            release.setBatchId(movement.getBatchId());
            release.setMovementType(InventoryConstants.MOVEMENT_WEB_ORDER_EXPIRY);
            release.setReferenceType(SalesConstants.STOCK_REFERENCE_TYPE_WEB_ORDER);
            release.setReferenceId(order.getId());
            release.setQuantityDelta(qtyBack);
            release.setUnitCost(movement.getUnitCost());
            release.setNotes("WhatsApp order expired — stock released");
            stockMovementRepository.save(release);
        }
    }

    /**
     * Merchant confirms an expired order: re-decrement stock for each line and
     * warn (via log) when a line cannot be reserved. The order itself is never
     * deleted or blocked (scope §11 "expiry is soft").
     */
    @Transactional
    public void reReserveStock(WebOrder order) {
        if (!WebOrderChannels.WHATSAPP.equals(order.getChannel())
                || !"expired".equals(order.getHandoffState())) {
            return;
        }
        List<WebOrderLine> lines = webOrderLineRepository.findByOrderIdOrderByLineIndexAsc(order.getId());
        for (WebOrderLine line : lines) {
            try {
                Item stockHolder = packageVariantStockResolver.requireInventoryHolder(
                        order.getBusinessId(), line.getItemId());
                reconcileCurrentStockFromBatches(stockHolder, order.getBusinessId(), order.getCatalogBranchId());
                inventoryBatchPickerService.pickAndApplyPhysicalDecrement(
                        order.getBusinessId(),
                        line.getItemId(),
                        order.getCatalogBranchId(),
                        line.getQuantity(),
                        SalesConstants.STOCK_REFERENCE_TYPE_WEB_ORDER,
                        order.getId(),
                        InventoryConstants.MOVEMENT_SALE,
                        null);
            } catch (Exception e) {
                log.warn("Re-reserve failed for expired WhatsApp order {} line {} (stock may be short): {}",
                        order.getId(), line.getItemId(), e.getMessage());
            }
        }
        // Fresh reservation — the expired marker no longer describes this order.
        order.setHandoffState(null);
        webOrderRepository.save(order);
    }

    /** Align item-level stock with summed batches before re-picking (mirrors checkout). */
    private void reconcileCurrentStockFromBatches(Item item, String businessId, String branchId) {
        List<Object[]> rows = inventoryBatchRepository.sumQuantityRemainingForItemsAtBranch(
                businessId,
                branchId,
                InventoryConstants.BATCH_STATUS_ACTIVE,
                List.of(item.getId()));
        BigDecimal sum = BigDecimal.ZERO;
        if (!rows.isEmpty() && rows.getFirst()[1] instanceof BigDecimal bd) {
            sum = bd;
        }
        item.setCurrentStock(round(sum));
        itemRepository.save(item);
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }
}
