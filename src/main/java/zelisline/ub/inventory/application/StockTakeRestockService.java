package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.StockTakeRestockDtos.GenerateRestockOrderResponse;
import zelisline.ub.inventory.api.dto.StockTakeRestockDtos.RestockOrderSummary;
import zelisline.ub.inventory.api.dto.StockTakeRestockDtos.StockTakeRestockItemResponse;
import zelisline.ub.inventory.api.dto.StockTakeRestockDtos.StockTakeRestockReviewResponse;
import zelisline.ub.inventory.api.dto.StockTakeRestockDtos.StockTakeRestockSupplierGroup;
import zelisline.ub.inventory.api.dto.StockTakeRestockDtos.StockTakeRestockSupplierOption;
import zelisline.ub.inventory.api.dto.StockTakeRestockDtos.StockTakeRestockSupplierOptionsResponse;
import zelisline.ub.inventory.api.dto.StockTakeRestockRequests.GenerateRestockOrderRequest;
import zelisline.ub.inventory.api.dto.StockTakeRestockRequests.PatchStockTakeRestockRequest;
import zelisline.ub.inventory.api.dto.StockTakeRestockRequests.PostDailyAuditRestockRequest;
import zelisline.ub.inventory.domain.DailyStockAudit;
import zelisline.ub.inventory.domain.StockTakeLine;
import zelisline.ub.inventory.domain.StockTakeRestockItem;
import zelisline.ub.inventory.domain.StockTakeSession;
import zelisline.ub.inventory.repository.DailyStockAuditRepository;
import zelisline.ub.inventory.repository.StockTakeRestockItemRepository;
import zelisline.ub.inventory.repository.StockTakeSessionRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class StockTakeRestockService {

    private static final ZoneId AUDIT_ZONE = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StockTakeRestockItemRepository restockItemRepository;
    private final StockTakeSessionRepository stockTakeSessionRepository;
    private final DailyStockAuditRepository dailyStockAuditRepository;
    private final DailyStockAuditService dailyStockAuditService;
    private final SupplierProductRepository supplierProductRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public StockTakeRestockSupplierOptionsResponse getSupplierOptions(
            String businessId,
            String sessionId,
            String lineId
    ) {
        ResolvedLine ctx = resolveDailyAuditLine(businessId, sessionId, lineId);
        List<SupplierProduct> links =
                supplierProductRepository.listForItem(businessId, ctx.line().getItemId()).stream()
                        .filter(sp -> sp.isActive() && sp.getDeletedAt() == null)
                        .sorted(
                                Comparator.comparing(SupplierProduct::isPrimaryLink)
                                        .reversed()
                                        .thenComparing(
                                                sp -> resolveBuyingPrice(sp),
                                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .toList();

        Map<String, Supplier> suppliersById = loadSuppliers(links.stream().map(SupplierProduct::getSupplierId).toList());
        List<StockTakeRestockSupplierOption> options =
                links.stream()
                        .map(sp -> toSupplierOption(sp, suppliersById.get(sp.getSupplierId())))
                        .toList();

        StockTakeRestockItemResponse pending = null;
        if (ctx.session().getDailyAuditId() != null) {
            List<StockTakeRestockItem> pendingRows =
                    restockItemRepository.findPendingForAuditItem(
                            businessId,
                            ctx.session().getBranchId(),
                            ctx.session().getDailyAuditId(),
                            ctx.line().getItemId(),
                            InventoryConstants.RESTOCK_STATUS_PENDING);
            if (!pendingRows.isEmpty()) {
                pending = toItemResponse(pendingRows.getFirst(), loadContextMaps(List.of(pendingRows.getFirst())));
            }
        }

        return new StockTakeRestockSupplierOptionsResponse(options, pending);
    }

    @Transactional
    public StockTakeRestockItemResponse upsertSuggestion(
            String businessId,
            String sessionId,
            String userId,
            @Valid PostDailyAuditRestockRequest body
    ) {
        ResolvedLine ctx = resolveDailyAuditLine(businessId, sessionId, body.lineId());
        SupplierProduct link = requireActiveSupplierLink(businessId, ctx.line().getItemId(), body.supplierId());
        Supplier supplier = supplierRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(body.supplierId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));

        String dailyAuditId = ctx.session().getDailyAuditId();
        StockTakeRestockItem row =
                dailyAuditId == null
                        ? null
                        : restockItemRepository
                                .findByBusinessIdAndBranchIdAndDailyAuditIdAndItemIdAndSupplierIdAndStatus(
                                        businessId,
                                        ctx.session().getBranchId(),
                                        dailyAuditId,
                                        ctx.line().getItemId(),
                                        body.supplierId(),
                                        InventoryConstants.RESTOCK_STATUS_PENDING)
                                .orElse(null);

        Instant now = Instant.now();
        if (row == null) {
            row = new StockTakeRestockItem();
            row.setBusinessId(businessId);
            row.setBranchId(ctx.session().getBranchId());
            row.setDailyAuditId(dailyAuditId);
            row.setStockTakeSessionId(sessionId);
            row.setStockTakeLineId(body.lineId());
            row.setItemId(ctx.line().getItemId());
            row.setSupplierId(body.supplierId());
            row.setAddedBy(userId);
            row.setAddedAt(now);
            row.setStatus(InventoryConstants.RESTOCK_STATUS_PENDING);
        } else {
            row.setStockTakeSessionId(sessionId);
            row.setStockTakeLineId(body.lineId());
        }

        row.setSuggestedQty(body.suggestedQty());
        row.setNote(blankToNull(body.note()));
        applySupplierSnapshot(row, link, supplier);
        restockItemRepository.save(row);
        return toItemResponse(row, loadContextMaps(List.of(row)));
    }

    @Transactional(readOnly = true)
    public StockTakeRestockReviewResponse getReview(
            String businessId,
            String branchId,
            LocalDate auditDate,
            String status,
            String supplierId
    ) {
        LocalDate date = dailyStockAuditService.resolveAuditDate(auditDate);
        DailyStockAudit audit =
                dailyStockAuditRepository
                        .findByBusinessBranchAndDateFetchItems(businessId, branchId, date)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "No daily audit manifest for this branch and date"));

        String effectiveStatus = normalizeStatusFilter(status);
        List<StockTakeRestockItem> rows =
                restockItemRepository.findForReview(
                        businessId,
                        branchId,
                        audit.getId(),
                        effectiveStatus,
                        blankToNull(supplierId));

        ContextMaps maps = loadContextMaps(rows);
        Map<String, List<StockTakeRestockItem>> bySupplier =
                rows.stream()
                        .collect(
                                Collectors.groupingBy(
                                        StockTakeRestockItem::getSupplierId,
                                        LinkedHashMap::new,
                                        Collectors.toList()));

        List<StockTakeRestockSupplierGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<StockTakeRestockItem>> entry : bySupplier.entrySet()) {
            Supplier supplier = maps.suppliers().get(entry.getKey());
            SupplierContact contact = maps.contacts().get(entry.getKey());
            List<StockTakeRestockItemResponse> itemResponses =
                    entry.getValue().stream().map(r -> toItemResponse(r, maps)).toList();
            BigDecimal subtotal =
                    itemResponses.stream()
                            .map(StockTakeRestockItemResponse::lineTotal)
                            .filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            groups.add(
                    new StockTakeRestockSupplierGroup(
                            entry.getKey(),
                            supplier != null ? supplier.getName() : "",
                            contact != null ? contact.getPhone() : null,
                            contact != null ? contact.getEmail() : null,
                            supplier != null ? supplier.getNotes() : null,
                            itemResponses,
                            subtotal));
        }

        return new StockTakeRestockReviewResponse(
                branchId,
                audit.getId(),
                audit.getAuditDate(),
                status == null || status.isBlank() ? "all" : status.trim(),
                groups);
    }

    @Transactional
    public StockTakeRestockItemResponse patchItem(
            String businessId,
            String restockItemId,
            @Valid PatchStockTakeRestockRequest body
    ) {
        StockTakeRestockItem row = requireEditable(businessId, restockItemId);
        if (body.supplierId() != null && !body.supplierId().isBlank()) {
            String newSupplierId = body.supplierId().trim();
            if (!newSupplierId.equals(row.getSupplierId())) {
                ensureNoPendingConflict(row, newSupplierId);
                SupplierProduct link = requireActiveSupplierLink(businessId, row.getItemId(), newSupplierId);
                Supplier supplier =
                        supplierRepository
                                .findByIdAndBusinessIdAndDeletedAtIsNull(newSupplierId, businessId)
                                .orElseThrow(
                                        () ->
                                                new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND, "Supplier not found"));
                row.setSupplierId(newSupplierId);
                applySupplierSnapshot(row, link, supplier);
            }
        }
        if (body.suggestedQty() != null) {
            row.setSuggestedQty(body.suggestedQty());
        }
        if (body.buyingPrice() != null) {
            row.setBuyingPrice(body.buyingPrice());
        }
        if (body.notes() != null) {
            row.setNote(blankToNull(body.notes()));
        }
        restockItemRepository.save(row);
        return toItemResponse(row, loadContextMaps(List.of(row)));
    }

    @Transactional
    public StockTakeRestockItemResponse approveItem(String businessId, String restockItemId, String userId) {
        StockTakeRestockItem row = requireItem(businessId, restockItemId);
        if (!InventoryConstants.RESTOCK_STATUS_PENDING.equals(row.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only pending items can be approved");
        }
        if (row.getBuyingPrice() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Buying price is required before approval");
        }
        row.setStatus(InventoryConstants.RESTOCK_STATUS_APPROVED);
        row.setReviewedBy(userId);
        row.setReviewedAt(Instant.now());
        row.setRejectionReason(null);
        restockItemRepository.save(row);
        return toItemResponse(row, loadContextMaps(List.of(row)));
    }

    @Transactional
    public StockTakeRestockItemResponse rejectItem(
            String businessId,
            String restockItemId,
            String userId,
            String reason
    ) {
        StockTakeRestockItem row = requireItem(businessId, restockItemId);
        if (!InventoryConstants.RESTOCK_STATUS_PENDING.equals(row.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only pending items can be rejected");
        }
        row.setStatus(InventoryConstants.RESTOCK_STATUS_REJECTED);
        row.setRejectionReason(reason.trim());
        row.setReviewedBy(userId);
        row.setReviewedAt(Instant.now());
        restockItemRepository.save(row);
        return toItemResponse(row, loadContextMaps(List.of(row)));
    }

    @Transactional
    public void deleteItem(String businessId, String restockItemId) {
        StockTakeRestockItem row = requireItem(businessId, restockItemId);
        if (!InventoryConstants.RESTOCK_STATUS_PENDING.equals(row.getStatus())
                && !InventoryConstants.RESTOCK_STATUS_REJECTED.equals(row.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Only pending or rejected items can be removed");
        }
        restockItemRepository.delete(row);
    }

    @Transactional
    public GenerateRestockOrderResponse generateOrder(
            String businessId,
            String branchId,
            LocalDate auditDate,
            String userId,
            GenerateRestockOrderRequest body
    ) {
        LocalDate date = dailyStockAuditService.resolveAuditDate(auditDate);
        DailyStockAudit audit =
                dailyStockAuditRepository
                        .findByBusinessBranchAndDateFetchItems(businessId, branchId, date)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "No daily audit manifest for this branch and date"));

        List<StockTakeRestockItem> approved =
                restockItemRepository
                        .findForReview(
                                businessId,
                                branchId,
                                audit.getId(),
                                InventoryConstants.RESTOCK_STATUS_APPROVED,
                                null)
                        .stream()
                        .filter(
                                r ->
                                        matchesFilter(
                                                r,
                                                body != null ? body.supplierIds() : null,
                                                body != null ? body.itemIds() : null))
                        .toList();

        if (approved.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No approved items to order");
        }

        Map<String, Supplier> suppliersById =
                loadSuppliers(approved.stream().map(StockTakeRestockItem::getSupplierId).toList());
        Map<String, List<StockTakeRestockItem>> bySupplier =
                approved.stream().collect(Collectors.groupingBy(StockTakeRestockItem::getSupplierId));

        Instant now = Instant.now();
        String datePart = LocalDate.now(AUDIT_ZONE).format(ORDER_DATE);
        List<RestockOrderSummary> orders = new ArrayList<>();

        for (Map.Entry<String, List<StockTakeRestockItem>> entry : bySupplier.entrySet()) {
            String orderNumber =
                    "RST-" + datePart + "-" + entry.getKey().substring(0, 8).toUpperCase();
            BigDecimal subtotal = BigDecimal.ZERO;
            for (StockTakeRestockItem row : entry.getValue()) {
                row.setStatus(InventoryConstants.RESTOCK_STATUS_ORDER_DRAFTED);
                row.setOrderNumber(orderNumber);
                row.setOrderDraftedBy(userId);
                row.setOrderDraftedAt(now);
                restockItemRepository.save(row);
                subtotal = subtotal.add(lineTotal(row.getSuggestedQty(), row.getBuyingPrice()));
            }
            Supplier supplier = suppliersById.get(entry.getKey());
            orders.add(
                    new RestockOrderSummary(
                            orderNumber,
                            entry.getKey(),
                            supplier != null ? supplier.getName() : "",
                            entry.getValue().size(),
                            subtotal,
                            InventoryConstants.RESTOCK_STATUS_ORDER_DRAFTED,
                            now));
        }

        return new GenerateRestockOrderResponse(orders);
    }

    @Transactional(readOnly = true)
    public List<RestockOrderSummary> listOrders(
            String businessId,
            Instant from,
            Instant to,
            String supplierId,
            String status
    ) {
        String effectiveStatus =
                status == null || status.isBlank() || "all".equalsIgnoreCase(status.trim())
                        ? null
                        : status.trim();

        List<StockTakeRestockItem> rows =
                restockItemRepository.findOrderHistory(
                        businessId, effectiveStatus, blankToNull(supplierId), from, to);

        Map<String, List<StockTakeRestockItem>> byOrder =
                rows.stream()
                        .filter(r -> r.getOrderNumber() != null)
                        .collect(
                                Collectors.groupingBy(
                                        StockTakeRestockItem::getOrderNumber,
                                        LinkedHashMap::new,
                                        Collectors.toList()));

        Map<String, Supplier> suppliersById =
                loadSuppliers(rows.stream().map(StockTakeRestockItem::getSupplierId).toList());
        List<RestockOrderSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, List<StockTakeRestockItem>> entry : byOrder.entrySet()) {
            List<StockTakeRestockItem> items = entry.getValue();
            StockTakeRestockItem first = items.getFirst();
            BigDecimal subtotal = BigDecimal.ZERO;
            for (StockTakeRestockItem row : items) {
                subtotal = subtotal.add(lineTotal(row.getSuggestedQty(), row.getBuyingPrice()));
            }
            Supplier supplier = suppliersById.get(first.getSupplierId());
            summaries.add(
                    new RestockOrderSummary(
                            entry.getKey(),
                            first.getSupplierId(),
                            supplier != null ? supplier.getName() : "",
                            items.size(),
                            subtotal,
                            first.getStatus(),
                            first.getOrderDraftedAt()));
        }
        return summaries;
    }

    @Transactional
    public void markOrderOrdered(String businessId, String orderNumber, String userId) {
        List<StockTakeRestockItem> rows = requireOrderRows(businessId, orderNumber);
        Instant now = Instant.now();
        for (StockTakeRestockItem row : rows) {
            if (!InventoryConstants.RESTOCK_STATUS_ORDER_DRAFTED.equals(row.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not in drafted state");
            }
            row.setStatus(InventoryConstants.RESTOCK_STATUS_ORDERED);
            row.setReviewedBy(userId);
            row.setReviewedAt(now);
            restockItemRepository.save(row);
        }
    }

    @Transactional
    public void markOrderReceived(String businessId, String orderNumber, String userId) {
        List<StockTakeRestockItem> rows = requireOrderRows(businessId, orderNumber);
        Instant now = Instant.now();
        for (StockTakeRestockItem row : rows) {
            if (!InventoryConstants.RESTOCK_STATUS_ORDERED.equals(row.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not in ordered state");
            }
            row.setStatus(InventoryConstants.RESTOCK_STATUS_RECEIVED);
            row.setReviewedBy(userId);
            row.setReviewedAt(now);
            restockItemRepository.save(row);
        }
    }

    private List<StockTakeRestockItem> requireOrderRows(String businessId, String orderNumber) {
        List<StockTakeRestockItem> rows =
                restockItemRepository.findByBusinessIdAndOrderNumber(businessId, orderNumber);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        return rows;
    }

    private static boolean matchesFilter(
            StockTakeRestockItem row, List<String> supplierIds, List<String> itemIds) {
        if (supplierIds != null && !supplierIds.isEmpty() && !supplierIds.contains(row.getSupplierId())) {
            return false;
        }
        if (itemIds != null && !itemIds.isEmpty() && !itemIds.contains(row.getItemId())) {
            return false;
        }
        return true;
    }

    private void ensureNoPendingConflict(StockTakeRestockItem row, String newSupplierId) {
        if (row.getDailyAuditId() == null) {
            return;
        }
        restockItemRepository
                .findByBusinessIdAndBranchIdAndDailyAuditIdAndItemIdAndSupplierIdAndStatus(
                        row.getBusinessId(),
                        row.getBranchId(),
                        row.getDailyAuditId(),
                        row.getItemId(),
                        newSupplierId,
                        InventoryConstants.RESTOCK_STATUS_PENDING)
                .filter(existing -> !existing.getId().equals(row.getId()))
                .ifPresent(
                        ignored -> {
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "A pending recommendation already exists for this supplier");
                        });
    }

    private StockTakeRestockItem requireEditable(String businessId, String restockItemId) {
        StockTakeRestockItem row = requireItem(businessId, restockItemId);
        if (!InventoryConstants.RESTOCK_STATUS_PENDING.equals(row.getStatus())
                && !InventoryConstants.RESTOCK_STATUS_APPROVED.equals(row.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Item cannot be edited in current status");
        }
        return row;
    }

    private StockTakeRestockItem requireItem(String businessId, String restockItemId) {
        return restockItemRepository
                .findByIdAndBusinessId(restockItemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Restock item not found"));
    }

    private SupplierProduct requireActiveSupplierLink(String businessId, String itemId, String supplierId) {
        return supplierProductRepository.listForItem(businessId, itemId).stream()
                .filter(sp -> supplierId.equals(sp.getSupplierId()))
                .filter(sp -> sp.isActive() && sp.getDeletedAt() == null)
                .findFirst()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "Supplier is not linked to this product"));
    }

    private ResolvedLine resolveDailyAuditLine(String businessId, String sessionId, String lineId) {
        StockTakeSession session =
                stockTakeSessionRepository
                        .findByIdAndBusinessIdFetchLines(sessionId, businessId)
                        .orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (!InventoryConstants.STOCKTAKE_SOURCE_DAILY_AUDIT.equals(session.getSource())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a daily audit session");
        }
        StockTakeLine line =
                session.getLines().stream()
                        .filter(l -> lineId.equals(l.getId()))
                        .findFirst()
                        .orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found"));
        return new ResolvedLine(session, line);
    }

    private static void applySupplierSnapshot(
            StockTakeRestockItem row, SupplierProduct link, Supplier supplier) {
        row.setBuyingPrice(resolveBuyingPrice(link));
        row.setSupplierPackSize(link.getPackSize());
        row.setSupplierPackUnit(link.getPackUnit());
        if (supplier != null && !"active".equalsIgnoreCase(supplier.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supplier is not active");
        }
    }

    private static BigDecimal resolveBuyingPrice(SupplierProduct link) {
        if (link.getDefaultCostPrice() != null) {
            return link.getDefaultCostPrice();
        }
        return link.getLastCostPrice();
    }

    private static StockTakeRestockSupplierOption toSupplierOption(SupplierProduct sp, Supplier supplier) {
        return new StockTakeRestockSupplierOption(
                sp.getSupplierId(),
                supplier != null ? supplier.getName() : "",
                sp.isPrimaryLink(),
                sp.getDefaultCostPrice(),
                sp.getLastCostPrice(),
                resolveBuyingPrice(sp),
                sp.getPackSize(),
                sp.getPackUnit(),
                sp.getLastPurchaseAt());
    }

    private StockTakeRestockItemResponse toItemResponse(StockTakeRestockItem row, ContextMaps maps) {
        Item item = maps.items().get(row.getItemId());
        Supplier supplier = maps.suppliers().get(row.getSupplierId());
        User addedBy = maps.users().get(row.getAddedBy());
        return new StockTakeRestockItemResponse(
                row.getId(),
                row.getBusinessId(),
                row.getBranchId(),
                row.getDailyAuditId(),
                row.getStockTakeSessionId(),
                row.getStockTakeLineId(),
                row.getItemId(),
                item != null ? item.getName() : "",
                item != null ? item.getSku() : null,
                row.getSupplierId(),
                supplier != null ? supplier.getName() : "",
                row.getSuggestedQty(),
                row.getBuyingPrice(),
                row.getSupplierPackSize(),
                row.getSupplierPackUnit(),
                lineTotal(row.getSuggestedQty(), row.getBuyingPrice()),
                row.getAddedBy(),
                addedBy != null ? displayName(addedBy) : "",
                row.getAddedAt(),
                row.getNote(),
                row.getStatus(),
                row.getRejectionReason(),
                row.getReviewedBy(),
                row.getReviewedAt(),
                row.getPurchaseOrderId(),
                row.getOrderNumber(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private static BigDecimal lineTotal(BigDecimal qty, BigDecimal price) {
        if (qty == null || price == null) {
            return null;
        }
        return qty.multiply(price).setScale(4, RoundingMode.HALF_UP);
    }

    private static String displayName(User user) {
        if (user.getName() != null && !user.getName().isBlank()) {
            return user.getName().trim();
        }
        return user.getEmail();
    }

    private Map<String, Supplier> loadSuppliers(List<String> supplierIds) {
        if (supplierIds.isEmpty()) {
            return Map.of();
        }
        return supplierRepository.findAllById(supplierIds).stream()
                .collect(Collectors.toMap(Supplier::getId, s -> s, (a, b) -> a));
    }

    private ContextMaps loadContextMaps(List<StockTakeRestockItem> rows) {
        Set<String> itemIds = rows.stream().map(StockTakeRestockItem::getItemId).collect(Collectors.toSet());
        Set<String> supplierIds =
                rows.stream().map(StockTakeRestockItem::getSupplierId).collect(Collectors.toSet());
        Set<String> userIds = rows.stream().map(StockTakeRestockItem::getAddedBy).collect(Collectors.toSet());

        Map<String, Item> items =
                itemRepository.findAllById(itemIds).stream()
                        .collect(Collectors.toMap(Item::getId, i -> i, (a, b) -> a));
        Map<String, Supplier> suppliers = loadSuppliers(new ArrayList<>(supplierIds));
        Map<String, User> users =
                userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        Map<String, SupplierContact> contacts = new LinkedHashMap<>();
        for (String supplierId : supplierIds) {
            List<SupplierContact> list =
                    supplierContactRepository.findBySupplierIdOrderByPrimaryContactDescNameAsc(supplierId);
            if (!list.isEmpty()) {
                contacts.put(supplierId, list.getFirst());
            }
        }
        return new ContextMaps(items, suppliers, users, contacts);
    }

    private static String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status.trim())) {
            return null;
        }
        return status.trim();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record ResolvedLine(StockTakeSession session, StockTakeLine line) {}

    private record ContextMaps(
            Map<String, Item> items,
            Map<String, Supplier> suppliers,
            Map<String, User> users,
            Map<String, SupplierContact> contacts) {}
}
