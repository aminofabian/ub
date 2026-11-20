package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.BatchSafetyCountsResponse;
import zelisline.ub.inventory.api.dto.BatchSafetyListResponse;
import zelisline.ub.inventory.api.dto.BatchSafetyRow;
import zelisline.ub.inventory.api.dto.RecallReportResponse;
import zelisline.ub.inventory.repository.BatchSafetyRepository;
import zelisline.ub.pharmacy.application.MedicineReceivingGuard;
import zelisline.ub.pharmacy.application.MedicineRegistry;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

/** Reads behind the stock-safety screens: quarantine, expiry, missing lot details, and recall by lot. */
@Service
@RequiredArgsConstructor
public class BatchSafetyQueryService {

    public static final String VIEW_QUARANTINED = "quarantined";
    public static final String VIEW_EXPIRED = "expired";
    public static final String VIEW_SHORT_DATED = "short_dated";
    public static final String VIEW_MISSING_DETAILS = "missing_details";

    public static final int DEFAULT_SHORT_DATED_DAYS = 90;
    private static final java.time.ZoneId SHOP_ZONE = java.time.ZoneId.of("Africa/Nairobi");
    /** Lower bound for "anything already expired"; a null date bind is not portable across databases. */
    private static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);
    private static final int MAX_ROWS = 500;
    private static final int MAX_TRACE_ROWS = 2000;

    private final BatchSafetyRepository batchSafetyRepository;
    private final BranchRepository branchRepository;
    private final MedicineRegistry medicineRegistry;
    private final CustomerRepository customerRepository;
    private final WebOrderRepository webOrderRepository;

    @Transactional(readOnly = true)
    public BatchSafetyListResponse list(String businessId, String view, String branchId, Integer days) {
        String v = view == null ? VIEW_QUARANTINED : view.strip().toLowerCase(Locale.ROOT);
        String branch = blankToNull(branchId);
        Pageable page = PageRequest.of(0, MAX_ROWS);
        LocalDate today = LocalDate.now(SHOP_ZONE);
        List<Object[]> raw;
        long total;
        switch (v) {
            case VIEW_QUARANTINED -> {
                raw = batchSafetyRepository.findByStatus(
                        businessId, InventoryConstants.BATCH_STATUS_QUARANTINED, branch, page);
                total = batchSafetyRepository.countByStatus(
                        businessId, InventoryConstants.BATCH_STATUS_QUARANTINED, branch);
            }
            case VIEW_EXPIRED -> {
                LocalDate until = today.minusDays(1);
                raw = batchSafetyRepository.findOnSaleByExpiryWindow(
                        businessId, branch, EARLIEST, until, BigDecimal.ZERO, page);
                total = batchSafetyRepository.countOnSaleByExpiryWindow(
                        businessId, branch, EARLIEST, until, BigDecimal.ZERO);
            }
            case VIEW_SHORT_DATED -> {
                LocalDate until = today.plusDays(shortDatedDays(days));
                raw = batchSafetyRepository.findOnSaleByExpiryWindow(
                        businessId, branch, today, until, BigDecimal.ZERO, page);
                total = batchSafetyRepository.countOnSaleByExpiryWindow(
                        businessId, branch, today, until, BigDecimal.ZERO);
            }
            case VIEW_MISSING_DETAILS -> {
                raw = batchSafetyRepository.findMedicineBatchesMissingDetails(
                        businessId, branch, BigDecimal.ZERO, page);
                total = batchSafetyRepository.countMedicineBatchesMissingDetails(
                        businessId, branch, BigDecimal.ZERO);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown view: " + view);
        }
        return new BatchSafetyListResponse(v, toRows(businessId, raw), total);
    }

    @Transactional(readOnly = true)
    public BatchSafetyCountsResponse counts(String businessId, String branchId, Integer days) {
        String branch = blankToNull(branchId);
        LocalDate today = LocalDate.now(SHOP_ZONE);
        int window = shortDatedDays(days);
        return new BatchSafetyCountsResponse(
                batchSafetyRepository.countByStatus(businessId, InventoryConstants.BATCH_STATUS_QUARANTINED, branch),
                batchSafetyRepository.countOnSaleByExpiryWindow(
                        businessId, branch, EARLIEST, today.minusDays(1), BigDecimal.ZERO),
                batchSafetyRepository.countOnSaleByExpiryWindow(
                        businessId, branch, today, today.plusDays(window), BigDecimal.ZERO),
                window,
                batchSafetyRepository.countMedicineBatchesMissingDetails(businessId, branch, BigDecimal.ZERO)
        );
    }

    /**
     * Traces a manufacturer lot. Till sales are found through {@code sale_items.batch_id}; online
     * orders keep no batch on their lines, so they are found through the stock movements that
     * reference the order.
     */
    @Transactional(readOnly = true)
    public RecallReportResponse recall(String businessId, String lotNumber, String itemId) {
        String lot = MedicineReceivingGuard.normalizeLot(lotNumber);
        if (lot == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the lot number to trace");
        }
        List<Object[]> raw = batchSafetyRepository.findByLot(
                businessId, lot.toLowerCase(Locale.ROOT), blankToNull(itemId), PageRequest.of(0, MAX_ROWS));
        List<BatchSafetyRow> batches = toRows(businessId, raw);
        Map<String, String> itemNames = new LinkedHashMap<>();
        Set<String> batchIds = new LinkedHashSet<>();
        BigDecimal onSale = BigDecimal.ZERO;
        BigDecimal quarantined = BigDecimal.ZERO;
        for (BatchSafetyRow r : batches) {
            batchIds.add(r.batchId());
            itemNames.put(r.itemId(), r.itemName());
            BigDecimal q = r.quantityRemaining() == null ? BigDecimal.ZERO : r.quantityRemaining();
            if (InventoryConstants.BATCH_STATUS_ACTIVE.equals(r.status())) {
                onSale = onSale.add(q);
            } else if (InventoryConstants.BATCH_STATUS_QUARANTINED.equals(r.status())) {
                quarantined = quarantined.add(q);
            }
        }
        if (batchIds.isEmpty()) {
            return new RecallReportResponse(lot, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(), false);
        }
        boolean truncated = raw.size() >= MAX_ROWS;

        List<Object[]> saleRows = batchSafetyRepository.findSaleLinesForBatches(
                businessId, batchIds, PageRequest.of(0, MAX_TRACE_ROWS));
        truncated = truncated || saleRows.size() >= MAX_TRACE_ROWS;
        Set<String> customerIds = new LinkedHashSet<>();
        for (Object[] row : saleRows) {
            String cid = ((Sale) row[1]).getCustomerId();
            if (cid != null && !cid.isBlank()) {
                customerIds.add(cid);
            }
        }
        Map<String, String> customerNames = new LinkedHashMap<>();
        if (!customerIds.isEmpty()) {
            for (Customer c : customerRepository.findByIdInAndBusinessId(customerIds, businessId)) {
                customerNames.put(c.getId(), c.getName());
            }
        }
        List<RecallReportResponse.SaleLine> sales = new ArrayList<>();
        for (Object[] row : saleRows) {
            SaleItem si = (SaleItem) row[0];
            Sale s = (Sale) row[1];
            sales.add(new RecallReportResponse.SaleLine(
                    s.getId(),
                    s.getReceiptNo(),
                    s.getStatus(),
                    s.getSoldAt(),
                    s.getBranchId(),
                    si.getItemId(),
                    si.getDisplayName() != null && !si.getDisplayName().isBlank()
                            ? si.getDisplayName()
                            : itemNames.get(si.getItemId()),
                    si.getQuantity(),
                    s.getCustomerId(),
                    s.getCustomerId() == null ? null : customerNames.get(s.getCustomerId())
            ));
        }

        List<StockMovement> webMoves = batchSafetyRepository.findMovementsForBatches(
                businessId, batchIds, SalesConstants.STOCK_REFERENCE_TYPE_WEB_ORDER, PageRequest.of(0, MAX_TRACE_ROWS));
        truncated = truncated || webMoves.size() >= MAX_TRACE_ROWS;
        // Net per order and item: an expired or voided order writes a positive movement back.
        Map<String, BigDecimal> netByOrderItem = new LinkedHashMap<>();
        Map<String, StockMovement> firstByOrderItem = new LinkedHashMap<>();
        for (StockMovement m : webMoves) {
            String key = m.getReferenceId() + "|" + m.getItemId();
            BigDecimal delta = m.getQuantityDelta() == null ? BigDecimal.ZERO : m.getQuantityDelta();
            netByOrderItem.merge(key, delta, BigDecimal::add);
            firstByOrderItem.putIfAbsent(key, m);
        }
        List<RecallReportResponse.OnlineOrderLine> online = new ArrayList<>();
        Map<String, WebOrder> orders = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : netByOrderItem.entrySet()) {
            BigDecimal sold = e.getValue().negate();
            if (sold.signum() <= 0) {
                continue;
            }
            StockMovement m = firstByOrderItem.get(e.getKey());
            WebOrder order = orders.computeIfAbsent(m.getReferenceId(),
                    id -> webOrderRepository.findByIdAndBusinessId(id, businessId).orElse(null));
            online.add(new RecallReportResponse.OnlineOrderLine(
                    m.getReferenceId(),
                    m.getCreatedAt(),
                    m.getItemId(),
                    itemNames.get(m.getItemId()),
                    sold,
                    order == null ? null : order.getCustomerName(),
                    order == null ? null : order.getCustomerPhone()
            ));
        }
        return new RecallReportResponse(lot, batches, onSale, quarantined, sales, online, truncated);
    }

    private List<BatchSafetyRow> toRows(String businessId, List<Object[]> raw) {
        Map<String, String> branchNames = new LinkedHashMap<>();
        for (Branch b : branchRepository.findByBusinessIdAndDeletedAtIsNull(businessId, Pageable.unpaged())) {
            branchNames.put(b.getId(), b.getName());
        }
        Set<String> itemIds = new LinkedHashSet<>();
        for (Object[] row : raw) {
            itemIds.add(((InventoryBatch) row[0]).getItemId());
        }
        Set<String> medicineIds = medicineRegistry.byItemId(businessId, itemIds).keySet();
        List<BatchSafetyRow> out = new ArrayList<>(raw.size());
        for (Object[] row : raw) {
            InventoryBatch b = (InventoryBatch) row[0];
            Item i = (Item) row[1];
            out.add(new BatchSafetyRow(
                    b.getId(),
                    b.getItemId(),
                    i.getName(),
                    i.getSku(),
                    b.getBranchId(),
                    branchNames.get(b.getBranchId()),
                    b.getBatchNumber(),
                    b.getLotNumber(),
                    b.getExpiryDate(),
                    b.getQuantityRemaining(),
                    b.getUnitCost(),
                    b.getStatus(),
                    b.getQuarantineReason(),
                    b.getQuarantinedAt(),
                    b.getReceivedAt(),
                    medicineIds.contains(b.getItemId())
            ));
        }
        return out;
    }

    private static int shortDatedDays(Integer days) {
        if (days == null) {
            return DEFAULT_SHORT_DATED_DAYS;
        }
        return Math.max(1, Math.min(730, days));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
