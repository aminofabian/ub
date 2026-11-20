package zelisline.ub.pharmacy.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.PostStockTransferRequest;
import zelisline.ub.inventory.application.InventoryTransferService;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.BranchNeed;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.CentralOrderRequest;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.CentralOrderResponse;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.Forecast;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.ForecastRow;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.OrderingSettings;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.PendingSplit;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.SplitLine;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos.SplitResult;
import zelisline.ub.pharmacy.domain.PharmacyProfile;
import zelisline.ub.pharmacy.domain.PurchaseOrderAllocation;
import zelisline.ub.pharmacy.repository.ItemMedicineDetailsRepository;
import zelisline.ub.pharmacy.repository.OrderingRepository;
import zelisline.ub.pharmacy.repository.PharmacyProfileRepository;
import zelisline.ub.pharmacy.repository.PurchaseOrderAllocationRepository;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.api.dto.AddPathAPurchaseOrderLineRequest;
import zelisline.ub.purchasing.api.dto.CreatePathAPurchaseOrderRequest;
import zelisline.ub.purchasing.api.dto.PathAPurchaseOrderDetailResponse;
import zelisline.ub.purchasing.application.PathAPurchaseService;
import zelisline.ub.purchasing.domain.PurchaseOrder;
import zelisline.ub.purchasing.repository.PurchaseOrderRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * What to order (ADR 0033, Phase 3). A moving average, on purpose: how fast each product sold
 * over the last four weeks, how long the stock lasts at that rate, and how much of it will
 * expire before it sells. For a medicine, "stock" is what the till would actually sell: expired
 * and undated lots are left out, and quarantined lots are not on sale at all.
 *
 * <p>Central purchasing is one order at a receiving branch plus a note of how much is meant for
 * each other branch; after the delivery is received those notes become draft transfers, which
 * carry each lot and expiry with them.
 */
@Service
@RequiredArgsConstructor
public class OrderingService {

    static final int WINDOW_DAYS = 28;
    private static final int MAX_ROWS = 500;
    private static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Nairobi");
    private static final List<String> SALE_TYPES = List.of(
            InventoryConstants.MOVEMENT_SALE, InventoryConstants.MOVEMENT_SALE_VOID, InventoryConstants.MOVEMENT_REFUND);
    private static final List<String> OPEN_ORDERS = List.of(PurchasingConstants.PO_DRAFT, PurchasingConstants.PO_SENT);

    private final OrderingRepository orderingRepository;
    private final ItemMedicineDetailsRepository medicineDetailsRepository;
    private final ItemRepository itemRepository;
    private final PharmacyProfileRepository profileRepository;
    private final PurchaseOrderAllocationRepository allocationRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PathAPurchaseService pathAPurchaseService;
    private final InventoryTransferService transferService;
    private final BranchRepository branchRepository;

    @Transactional(readOnly = true)
    public OrderingSettings settings(String businessId) {
        return profileRepository.findById(businessId)
                .map(p -> new OrderingSettings(p.getOrderLeadDays(), p.getOrderCoverDays()))
                .orElse(new OrderingSettings(7, 30));
    }

    @Transactional
    public OrderingSettings saveSettings(String businessId, OrderingSettings req) {
        PharmacyProfile p = profileRepository.findById(businessId).orElseGet(() -> {
            PharmacyProfile created = new PharmacyProfile();
            created.setBusinessId(businessId);
            return created;
        });
        p.setOrderLeadDays(req.leadDays());
        p.setOrderCoverDays(req.coverDays());
        profileRepository.save(p);
        return new OrderingSettings(p.getOrderLeadDays(), p.getOrderCoverDays());
    }

    /** One stock line of a product at a branch: a quantity and when it expires (null when undated). */
    record Lot(LocalDate expiry, BigDecimal quantity) {
    }

    /**
     * The need of one product at one branch. Pure arithmetic, so it can be tested without a
     * database. {@code lots} is everything on sale; for a medicine, expired and undated lots do
     * not count as sellable.
     */
    static BranchNeed need(
            String branchId, BigDecimal soldInWindow, List<Lot> lots, BigDecimal onOrder,
            boolean medicine, LocalDate today, int windowDays, int leadDays, int coverDays) {
        BigDecimal sold = soldInWindow.max(BigDecimal.ZERO);
        BigDecimal rate = sold.divide(BigDecimal.valueOf(windowDays), 6, RoundingMode.HALF_UP);
        List<Lot> usable = new ArrayList<>();
        for (Lot l : lots) {
            if (medicine && (l.expiry() == null || l.expiry().isBefore(today))) {
                continue;
            }
            usable.add(l);
        }
        usable.sort(Comparator.comparing(Lot::expiry, Comparator.nullsLast(Comparator.naturalOrder())));
        BigDecimal sellable = BigDecimal.ZERO;
        BigDecimal expiringFirst = BigDecimal.ZERO;
        BigDecimal consumed = BigDecimal.ZERO;
        for (Lot l : usable) {
            sellable = sellable.add(l.quantity());
            if (l.expiry() == null) {
                continue;
            }
            // Earliest expiry sells first. By this lot's expiry the shop can have sold rate x days in
            // all; what earlier lots already took is not available to this one.
            long days = Math.max(0, ChronoUnit.DAYS.between(today, l.expiry()));
            BigDecimal canSell = rate.multiply(BigDecimal.valueOf(days)).subtract(consumed).max(BigDecimal.ZERO);
            BigDecimal willSell = l.quantity().min(canSell);
            expiringFirst = expiringFirst.add(l.quantity().subtract(willSell));
            consumed = consumed.add(willSell);
        }
        BigDecimal daysOfCover = rate.signum() == 0 ? null : sellable.divide(rate, 1, RoundingMode.HALF_UP);
        BigDecimal wanted = rate.multiply(BigDecimal.valueOf((long) leadDays + coverDays));
        BigDecimal suggested = wanted
                .subtract(sellable.subtract(expiringFirst))
                .subtract(onOrder.max(BigDecimal.ZERO))
                .max(BigDecimal.ZERO)
                .setScale(0, RoundingMode.CEILING);
        return new BranchNeed(
                branchId, scale(sold), rate.setScale(3, RoundingMode.HALF_UP), scale(sellable), scale(onOrder),
                daysOfCover, scale(expiringFirst), suggested);
    }

    /** The forecast for one branch, or for every branch when {@code branchId} is null. */
    @Transactional(readOnly = true)
    public Forecast forecast(String businessId, String branchId) {
        OrderingSettings settings = settings(businessId);
        LocalDate today = LocalDate.now(SHOP_ZONE);
        Instant since = today.minusDays(WINDOW_DAYS).atStartOfDay(SHOP_ZONE).toInstant();
        String only = branchId == null || branchId.isBlank() ? null : branchId.strip();

        Map<String, Map<String, BigDecimal>> sold = new HashMap<>();
        for (Object[] r : orderingRepository.soldSince(businessId, SALE_TYPES, since)) {
            // Sale movements are negative, their reversals positive: sold is the negated sum.
            put(sold, (String) r[0], (String) r[1], ((BigDecimal) r[2]).negate());
        }
        Map<String, Map<String, List<Lot>>> lots = new HashMap<>();
        Map<String, BigDecimal> cost = new HashMap<>();
        for (Object[] r : orderingRepository.stockOnSale(businessId)) {
            lots.computeIfAbsent((String) r[0], k -> new HashMap<>())
                    .computeIfAbsent((String) r[1], k -> new ArrayList<>())
                    .add(new Lot((LocalDate) r[2], (BigDecimal) r[3]));
            if (r[4] != null) {
                cost.merge((String) r[0], (BigDecimal) r[4], BigDecimal::max);
            }
        }
        Map<String, Map<String, BigDecimal>> onOrder = new HashMap<>();
        for (Object[] r : orderingRepository.onOrder(businessId, OPEN_ORDERS)) {
            put(onOrder, (String) r[0], (String) r[1], (BigDecimal) r[2]);
        }
        Set<String> medicines = new HashSet<>(medicineDetailsRepository.findItemIdsByBusinessId(businessId));

        // Only what sold in the window can have a rate, so only that can be forecast.
        record Draft(String itemId, List<BranchNeed> branches, BigDecimal suggested, BigDecimal expiring, BigDecimal cover) {
        }
        List<Draft> drafts = new ArrayList<>();
        for (Map.Entry<String, Map<String, BigDecimal>> e : sold.entrySet()) {
            String itemId = e.getKey();
            List<BranchNeed> needs = new ArrayList<>();
            BigDecimal suggested = BigDecimal.ZERO;
            BigDecimal expiring = BigDecimal.ZERO;
            BigDecimal cover = null;
            for (Map.Entry<String, BigDecimal> b : new TreeMap<>(e.getValue()).entrySet()) {
                if (only != null && !only.equals(b.getKey())) {
                    continue;
                }
                if (b.getValue().signum() <= 0) {
                    continue;
                }
                BranchNeed n = need(
                        b.getKey(), b.getValue(),
                        lots.getOrDefault(itemId, Map.of()).getOrDefault(b.getKey(), List.of()),
                        onOrder.getOrDefault(itemId, Map.of()).getOrDefault(b.getKey(), BigDecimal.ZERO),
                        medicines.contains(itemId), today, WINDOW_DAYS, settings.leadDays(), settings.coverDays());
                if (n.suggested().signum() == 0 && n.expiringFirst().signum() == 0) {
                    continue;
                }
                needs.add(n);
                suggested = suggested.add(n.suggested());
                expiring = expiring.add(n.expiringFirst());
                if (n.daysOfCover() != null && (cover == null || n.daysOfCover().compareTo(cover) < 0)) {
                    cover = n.daysOfCover();
                }
            }
            if (!needs.isEmpty()) {
                drafts.add(new Draft(itemId, needs, suggested, expiring, cover));
            }
        }
        drafts.sort(Comparator.comparing(Draft::cover, Comparator.nullsLast(Comparator.naturalOrder())));
        boolean truncated = drafts.size() > MAX_ROWS;
        if (truncated) {
            drafts = drafts.subList(0, MAX_ROWS);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Draft d : drafts) {
            ids.add(d.itemId());
        }
        Map<String, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Item item : itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(ids, businessId)) {
                names.put(item.getId(), item.getName());
            }
        }
        List<ForecastRow> rows = new ArrayList<>();
        for (Draft d : drafts) {
            String name = names.get(d.itemId());
            if (name == null) {
                continue; // deleted product
            }
            rows.add(new ForecastRow(
                    d.itemId(), name, medicines.contains(d.itemId()), cost.get(d.itemId()),
                    d.suggested(), d.expiring(), d.cover(), d.branches()));
        }
        return new Forecast(WINDOW_DAYS, settings.leadDays(), settings.coverDays(), truncated, rows);
    }

    /**
     * One draft order at the receiving branch for the total of every line, with a note of what is
     * meant for each other branch. Nothing is sent to the supplier; the order is a draft.
     */
    @Transactional
    public CentralOrderResponse createCentralOrder(String businessId, CentralOrderRequest req, String userId) {
        String receiving = req.receivingBranchId().strip();
        Set<String> branches = new LinkedHashSet<>();
        for (CentralOrderRequest.Line line : req.lines()) {
            for (CentralOrderRequest.Share share : line.shares()) {
                branches.add(share.branchId().strip());
            }
        }
        for (String branch : branches) {
            branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branch, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
        }
        // createPurchaseOrder checks that the supplier and the receiving branch are this business's.
        PathAPurchaseOrderDetailResponse po = pathAPurchaseService.createPurchaseOrder(
                businessId,
                new CreatePathAPurchaseOrderRequest(
                        req.supplierId().strip(), receiving, null, null,
                        branches.size() > 1 || !branches.contains(receiving)
                                ? "Central order: to be shared between branches after delivery"
                                : null));
        Set<String> seen = new HashSet<>();
        Set<String> supplied = new LinkedHashSet<>();
        for (CentralOrderRequest.Line line : req.lines()) {
            String itemId = line.itemId().strip();
            if (!seen.add(itemId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A product is listed twice");
            }
            Map<String, BigDecimal> perBranch = new LinkedHashMap<>();
            BigDecimal total = BigDecimal.ZERO;
            for (CentralOrderRequest.Share share : line.shares()) {
                perBranch.merge(share.branchId().strip(), share.quantity(), BigDecimal::add);
                total = total.add(share.quantity());
            }
            // addPurchaseOrderLine checks that the product is this business's.
            pathAPurchaseService.addPurchaseOrderLine(
                    businessId, po.id(), new AddPathAPurchaseOrderLineRequest(itemId, total, line.unitCost()));
            for (Map.Entry<String, BigDecimal> e : perBranch.entrySet()) {
                if (e.getKey().equals(receiving)) {
                    continue;
                }
                PurchaseOrderAllocation a = new PurchaseOrderAllocation();
                a.setBusinessId(businessId);
                a.setPurchaseOrderId(po.id());
                a.setItemId(itemId);
                a.setBranchId(e.getKey());
                a.setQuantity(e.getValue().setScale(4, RoundingMode.HALF_UP));
                a.setCreatedBy(userId);
                allocationRepository.save(a);
                supplied.add(e.getKey());
            }
        }
        return new CentralOrderResponse(po.id(), po.poNumber(), req.lines().size(), supplied.size());
    }

    /** Central orders with stock still to be shared out. */
    @Transactional(readOnly = true)
    public List<PendingSplit> pendingSplits(String businessId) {
        Map<String, List<PurchaseOrderAllocation>> byOrder = new LinkedHashMap<>();
        Set<String> itemIds = new LinkedHashSet<>();
        for (PurchaseOrderAllocation a : allocationRepository
                .findByBusinessIdAndTransferIdIsNullOrderByCreatedAtAsc(businessId)) {
            byOrder.computeIfAbsent(a.getPurchaseOrderId(), k -> new ArrayList<>()).add(a);
            itemIds.add(a.getItemId());
        }
        Map<String, String> names = new HashMap<>();
        if (!itemIds.isEmpty()) {
            for (Item item : itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(itemIds, businessId)) {
                names.put(item.getId(), item.getName());
            }
        }
        List<PendingSplit> out = new ArrayList<>();
        for (Map.Entry<String, List<PurchaseOrderAllocation>> e : byOrder.entrySet()) {
            PurchaseOrder po = purchaseOrderRepository.findByIdAndBusinessId(e.getKey(), businessId).orElse(null);
            if (po == null || PurchasingConstants.PO_CANCELLED.equals(po.getStatus())) {
                continue;
            }
            List<SplitLine> lines = new ArrayList<>();
            for (PurchaseOrderAllocation a : e.getValue()) {
                lines.add(new SplitLine(
                        a.getItemId(), names.getOrDefault(a.getItemId(), "Product"), a.getBranchId(), a.getQuantity()));
            }
            out.add(new PendingSplit(
                    po.getId(), po.getPoNumber(), po.getStatus(), po.getBranchId(),
                    hasArrived(po), lines));
        }
        return out;
    }

    /**
     * Drafts one transfer per branch from the receiving branch, for a central order that has been
     * received. The transfers are drafts: someone still sends and receives them, and that is where
     * stock is checked and each lot and expiry is carried across.
     */
    @Transactional
    public SplitResult draftTransfers(String businessId, String purchaseOrderId, String userId) {
        PurchaseOrder po = purchaseOrderRepository.findByIdAndBusinessId(purchaseOrderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!hasArrived(po)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Receive this order first. Stock can only be shared out once it has arrived.");
        }
        Map<String, List<PurchaseOrderAllocation>> byBranch = new LinkedHashMap<>();
        for (PurchaseOrderAllocation a : allocationRepository.findByBusinessIdAndPurchaseOrderId(businessId, po.getId())) {
            if (a.getTransferId() == null) {
                byBranch.computeIfAbsent(a.getBranchId(), k -> new ArrayList<>()).add(a);
            }
        }
        if (byBranch.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order has already been shared out");
        }
        List<String> transferIds = new ArrayList<>();
        for (Map.Entry<String, List<PurchaseOrderAllocation>> e : byBranch.entrySet()) {
            List<PostStockTransferRequest.Line> lines = new ArrayList<>();
            for (PurchaseOrderAllocation a : e.getValue()) {
                lines.add(new PostStockTransferRequest.Line(a.getItemId(), a.getQuantity()));
            }
            String transferId = transferService.createDraft(
                    businessId,
                    new PostStockTransferRequest(
                            po.getBranchId(), e.getKey(), "Share of order " + po.getPoNumber(), lines),
                    userId).id();
            for (PurchaseOrderAllocation a : e.getValue()) {
                a.setTransferId(transferId);
                allocationRepository.save(a);
            }
            transferIds.add(transferId);
        }
        return new SplitResult(transferIds);
    }

    /**
     * True once any of the order has been received. A part delivery can be shared out too: the
     * transfers are drafts, and sending one is where the stock on hand is checked.
     */
    private static boolean hasArrived(PurchaseOrder po) {
        return PurchasingConstants.PO_RECEIVED.equals(po.getStatus())
                || PurchasingConstants.DELIVERY_DELIVERED.equals(po.getDeliveryStatus())
                || PurchasingConstants.DELIVERY_PARTIALLY_DELIVERED.equals(po.getDeliveryStatus());
    }

    private static void put(Map<String, Map<String, BigDecimal>> map, String itemId, String branchId, BigDecimal v) {
        map.computeIfAbsent(itemId, k -> new HashMap<>()).merge(branchId, v, BigDecimal::add);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(3, RoundingMode.HALF_UP);
    }
}
