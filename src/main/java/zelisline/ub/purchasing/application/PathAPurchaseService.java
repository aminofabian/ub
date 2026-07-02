package zelisline.ub.purchasing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.application.BatchNumberGenerator;
import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.domain.IdempotencyKey;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.IdempotencyKeyRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerAccountResolver;
import zelisline.ub.finance.application.LedgerPostingPort;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.inventory.domain.SupplyBatch;
import zelisline.ub.inventory.repository.SupplyBatchRepository;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.api.dto.AddPathAPurchaseOrderLineRequest;
import zelisline.ub.purchasing.api.dto.CreatePathAPurchaseOrderRequest;
import zelisline.ub.purchasing.api.dto.PathAPurchaseOrderDetailResponse;
import zelisline.ub.purchasing.api.dto.PathAPurchaseOrderLineResponse;
import zelisline.ub.purchasing.api.dto.PathAPurchaseOrderListRow;
import zelisline.ub.purchasing.api.dto.PostGoodsReceiptLineInput;
import zelisline.ub.purchasing.api.dto.PostGoodsReceiptRequest;
import zelisline.ub.purchasing.api.dto.PostGoodsReceiptResponse;
import zelisline.ub.purchasing.api.dto.PostGrnSupplierInvoiceLineInput;
import zelisline.ub.purchasing.api.dto.PostGrnSupplierInvoiceRequest;
import zelisline.ub.purchasing.api.dto.PostGrnSupplierInvoiceResponse;
import zelisline.ub.purchasing.domain.GoodsReceipt;
import zelisline.ub.purchasing.domain.GoodsReceiptLine;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.PurchaseOrder;
import zelisline.ub.purchasing.domain.PurchaseOrderLine;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.domain.SupplierInvoiceLine;
import zelisline.ub.purchasing.repository.GoodsReceiptLineRepository;
import zelisline.ub.purchasing.repository.GoodsReceiptRepository;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.PurchaseOrderLineRepository;
import zelisline.ub.purchasing.repository.PurchaseOrderRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class PathAPurchaseService {

    private static final BigDecimal MONEY_SCALE = new BigDecimal("0.01");
    private static final int UNIT_SCALE = 4;

    private final BatchNumberGenerator batchNumberGenerator;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final GoodsReceiptLineRepository goodsReceiptLineRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final StockMovementRepository stockMovementRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierInvoiceLineRepository supplierInvoiceLineRepository;
    private final LedgerPostingPort ledgerPostingPort;
    private final LedgerAccountResolver ledgerAccountResolver;
    private final ItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final BranchRepository branchRepository;
    private final BusinessRepository businessRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;
    private final SupplyBatchRepository supplyBatchRepository;
    private final PackageVariantStockResolver packageVariantStockResolver;

    public static String postGrnRoute() {
        return "POST /api/v1/purchasing/path-a/goods-receipts";
    }

    public static String postGrnInvoiceRoute(String grnId) {
        return "POST /api/v1/purchasing/path-a/goods-receipts/%s/supplier-invoice".formatted(grnId);
    }

    @Transactional(readOnly = true)
    public PathAPurchaseOrderDetailResponse getPurchaseOrder(String businessId, String purchaseOrderId) {
        PurchaseOrder po = loadPo(businessId, purchaseOrderId);
        return detailOf(po);
    }

    @Transactional(readOnly = true)
    public List<PathAPurchaseOrderListRow> listPurchaseOrders(String businessId, String supplierId, String status) {
        String effectiveStatus = status != null && !status.isBlank() ? status.trim() : PurchasingConstants.PO_SENT;
        List<PurchaseOrder> orders = supplierId != null && !supplierId.isBlank()
                ? purchaseOrderRepository.findByBusinessIdAndSupplierIdAndStatusOrderByCreatedAtDesc(
                        businessId, supplierId.trim(), effectiveStatus)
                : purchaseOrderRepository.findByBusinessIdAndStatusOrderByCreatedAtDesc(businessId, effectiveStatus);
        return orders.stream().map(this::toListRow).toList();
    }

    private PathAPurchaseOrderListRow toListRow(PurchaseOrder po) {
        List<PurchaseOrderLine> lines = purchaseOrderLineRepository
                .findByPurchaseOrderIdOrderBySortOrderAscIdAsc(po.getId());
        BigDecimal totalOrdered = BigDecimal.ZERO;
        BigDecimal totalReceived = BigDecimal.ZERO;
        for (PurchaseOrderLine l : lines) {
            totalOrdered = totalOrdered.add(l.getQtyOrdered());
            totalReceived = totalReceived.add(l.getQtyReceived());
        }
        return new PathAPurchaseOrderListRow(
                po.getId(),
                po.getSupplierId(),
                po.getBranchId(),
                po.getPoNumber(),
                po.getExpectedDate(),
                po.getStatus(),
                lines.size(),
                totalOrdered.setScale(4, RoundingMode.HALF_UP),
                totalReceived.setScale(4, RoundingMode.HALF_UP)
        );
    }

    @Transactional
    public PathAPurchaseOrderDetailResponse createPurchaseOrder(String businessId, CreatePathAPurchaseOrderRequest req) {
        assertSupplierInBusiness(businessId, req.supplierId());
        assertBranchInBusiness(businessId, req.branchId());
        PurchaseOrder po = new PurchaseOrder();
        String id = UUID.randomUUID().toString();
        po.setId(id);
        po.setBusinessId(businessId);
        po.setSupplierId(req.supplierId());
        po.setBranchId(req.branchId());
        po.setExpectedDate(req.expectedDate());
        po.setNotes(blankToNull(req.notes()));
        po.setStatus(PurchasingConstants.PO_DRAFT);
        String num = blankToNull(req.poNumber());
        po.setPoNumber(num != null ? num : "PO-" + id.replace("-", "").substring(0, 8).toUpperCase());
        try {
            purchaseOrderRepository.save(po);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PO number already exists");
        }
        return detailOf(po);
    }

    @Transactional
    public PathAPurchaseOrderLineResponse addPurchaseOrderLine(
            String businessId,
            String purchaseOrderId,
            AddPathAPurchaseOrderLineRequest req
    ) {
        PurchaseOrder po = loadPo(businessId, purchaseOrderId);
        assertPoDraft(po);
        itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(req.itemId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setPurchaseOrderId(po.getId());
        line.setSortOrder(purchaseOrderLineRepository.maxSortOrder(po.getId()) + 1);
        line.setItemId(req.itemId());
        line.setQtyOrdered(req.qtyOrdered().setScale(UNIT_SCALE, RoundingMode.HALF_UP));
        line.setUnitEstimatedCost(req.unitEstimatedCost().setScale(UNIT_SCALE, RoundingMode.HALF_UP));
        line.setQtyReceived(BigDecimal.ZERO);
        purchaseOrderLineRepository.save(line);
        return lineResponse(line);
    }

    @Transactional
    public PathAPurchaseOrderDetailResponse sendPurchaseOrder(String businessId, String purchaseOrderId) {
        PurchaseOrder po = loadPo(businessId, purchaseOrderId);
        assertPoDraft(po);
        long n = purchaseOrderLineRepository.findByPurchaseOrderIdOrderBySortOrderAscIdAsc(po.getId()).size();
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Purchase order has no lines");
        }
        po.setStatus(PurchasingConstants.PO_SENT);
        purchaseOrderRepository.save(po);
        return detailOf(po);
    }

    @Transactional
    public PathAPurchaseOrderDetailResponse cancelPurchaseOrder(String businessId, String purchaseOrderId) {
        PurchaseOrder po = loadPo(businessId, purchaseOrderId);
        if (PurchasingConstants.PO_CANCELLED.equals(po.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Purchase order already cancelled");
        }
        po.setStatus(PurchasingConstants.PO_CANCELLED);
        purchaseOrderRepository.save(po);
        return detailOf(po);
    }

    @Transactional
    public PostGoodsReceiptResponse postGoodsReceipt(String businessId, PostGoodsReceiptRequest req, String idemKey) {
        if (idemKey != null && !idemKey.isBlank()) {
            return postGrnWithIdempotency(businessId, req, idemKey.trim());
        }
        return executePostGrn(businessId, req);
    }

    private PostGoodsReceiptResponse postGrnWithIdempotency(String businessId, PostGoodsReceiptRequest req, String keyRaw) {
        String route = postGrnRoute();
        String keyHash = TokenHasher.sha256Hex(keyRaw);
        synchronized ((businessId + "|" + route + "|" + keyHash).intern()) {
            String bodyJson;
            try {
                bodyJson = objectMapper.writeValueAsString(req);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            String bodyHash = TokenHasher.sha256Hex(bodyJson);
            Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByBusinessIdAndKeyHashAndRoute(
                    businessId, keyHash, route);
            if (existing.isPresent()) {
                IdempotencyKey row = existing.get();
                if (!row.getBodyHash().equals(bodyHash)) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "Idempotency key already used with a different request body");
                }
                try {
                    return objectMapper.readValue(row.getResponseJson(), PostGoodsReceiptResponse.class);
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }
            PostGoodsReceiptResponse response = executePostGrn(businessId, req);
            persistIdem(businessId, keyHash, bodyHash, route, response);
            return response;
        }
    }

    private void persistIdem(String businessId, String keyHash, String bodyHash, String route, PostGoodsReceiptResponse response) {
        String json;
        try {
            json = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        IdempotencyKey row = new IdempotencyKey();
        row.setBusinessId(businessId);
        row.setKeyHash(keyHash);
        row.setRoute(route);
        row.setBodyHash(bodyHash);
        row.setHttpStatus(HttpStatus.OK.value());
        row.setResponseJson(json);
        try {
            idempotencyKeyRepository.save(row);
        } catch (DataIntegrityViolationException e) {
            IdempotencyKey replay = idempotencyKeyRepository
                    .findByBusinessIdAndKeyHashAndRoute(businessId, keyHash, route)
                    .orElseThrow(() -> e);
            if (!replay.getBodyHash().equals(bodyHash)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Idempotency key already used with a different request body");
            }
        }
    }

    private PostGoodsReceiptResponse executePostGrn(String businessId, PostGoodsReceiptRequest req) {
        PurchaseOrder po = loadPo(businessId, req.purchaseOrderId());
        if (!PurchasingConstants.PO_SENT.equals(po.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Purchase order must be sent to receive goods");
        }
        if (req.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Goods receipt must have lines");
        }
        if (!po.getBranchId().equals(req.branchId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch must match purchase order");
        }
        assertBranchInBusiness(businessId, req.branchId());
        validateGrnLineIds(req);

        GoodsReceipt grn = new GoodsReceipt();
        grn.setBusinessId(businessId);
        grn.setPurchaseOrderId(po.getId());
        grn.setBranchId(req.branchId());
        grn.setReceivedAt(req.receivedAt());
        grn.setNotes(blankToNull(req.notes()));
        grn.setStatus(PurchasingConstants.GRN_DRAFT);
        goodsReceiptRepository.save(grn);

        SupplyBatch sb = new SupplyBatch();
        sb.setBusinessId(businessId);
        sb.setBranchId(grn.getBranchId());
        sb.setSupplierId(po.getSupplierId());
        sb.setBatchNumber(batchNumberGenerator.next(po.getSupplierId(), supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(po.getSupplierId(), businessId).map(zelisline.ub.suppliers.domain.Supplier::getName).orElse(null), grn.getReceivedAt(), businessId));
        sb.setBatchName(null);
        sb.setSourceType(PurchasingConstants.BATCH_SOURCE_PATH_A_GRN);
        sb.setSourceId(grn.getId());
        sb.setItemCount(0);
        sb.setTotalInitialQuantity(BigDecimal.ZERO);
        sb.setTotalRemainingQuantity(BigDecimal.ZERO);
        sb.setReceivedAt(grn.getReceivedAt());
        sb.setStatus("active");
        supplyBatchRepository.save(sb);

        BigDecimal grniTotal = BigDecimal.ZERO;
        List<GoodsReceiptLine> createdLines = new ArrayList<>();
        int itemCount = 0;
        BigDecimal totalInitial = BigDecimal.ZERO;
        BigDecimal totalRemaining = BigDecimal.ZERO;
        for (PostGoodsReceiptLineInput in : req.lines()) {
            PurchaseOrderLine pol = purchaseOrderLineRepository.findById(in.purchaseOrderLineId())
                    .filter(l -> l.getPurchaseOrderId().equals(po.getId()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid purchase order line"));
            if (in.qtyReceived().signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be positive");
            }
            BigDecimal remaining = pol.getQtyOrdered().subtract(pol.getQtyReceived());
            if (in.qtyReceived().compareTo(remaining) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receive quantity exceeds open quantity on line");
            }
            PackageVariantStockResolver.StockPickResolution inbound = packageVariantStockResolver.resolveInbound(
                    businessId, pol.getItemId(), in.qtyReceived());
            Item item = packageVariantStockResolver.requireInventoryHolder(businessId, inbound.stockItemId());
            BigDecimal unitCost = pol.getUnitEstimatedCost();
            BigDecimal lineMoney = inbound.stockQuantity().multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
            grniTotal = grniTotal.add(lineMoney);

            GoodsReceiptLine gl = new GoodsReceiptLine();
            gl.setGoodsReceiptId(grn.getId());
            gl.setPurchaseOrderLineId(pol.getId());
            gl.setQtyReceived(in.qtyReceived().setScale(UNIT_SCALE, RoundingMode.HALF_UP));
            goodsReceiptLineRepository.save(gl);
            createdLines.add(gl);

            InventoryBatch batch = new InventoryBatch();
            batch.setBusinessId(businessId);
            batch.setBranchId(grn.getBranchId());
            batch.setItemId(inbound.stockItemId());
            batch.setSupplierId(po.getSupplierId());
            batch.setBatchNumber("A-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            batch.setSupplyBatchId(sb.getId());
            batch.setSourceType(PurchasingConstants.BATCH_SOURCE_PATH_A_GRN);
            batch.setSourceId(gl.getId());
            batch.setInitialQuantity(inbound.stockQuantity());
            batch.setQuantityRemaining(inbound.stockQuantity());
            batch.setUnitCost(unitCost);
            batch.setReceivedAt(grn.getReceivedAt());
            inventoryBatchRepository.save(batch);
            itemCount++;
            totalInitial = totalInitial.add(inbound.stockQuantity());
            totalRemaining = totalRemaining.add(inbound.stockQuantity());

            StockMovement sm = new StockMovement();
            sm.setBusinessId(businessId);
            sm.setBranchId(grn.getBranchId());
            sm.setItemId(inbound.stockItemId());
            sm.setBatchId(batch.getId());
            sm.setMovementType(PurchasingConstants.MOVEMENT_RECEIPT);
            sm.setReferenceType(PurchasingConstants.STOCK_REF_GRN_LINE);
            sm.setReferenceId(gl.getId());
            sm.setQuantityDelta(inbound.stockQuantity());
            sm.setUnitCost(unitCost);
            stockMovementRepository.save(sm);

            BigDecimal base = item.getCurrentStock() == null ? BigDecimal.ZERO : item.getCurrentStock();
            item.setCurrentStock(base.add(inbound.stockQuantity()));
            itemRepository.save(item);

            pol.setQtyReceived(pol.getQtyReceived().add(in.qtyReceived()));
            purchaseOrderLineRepository.save(pol);

            gl.setInventoryBatchId(batch.getId());
            goodsReceiptLineRepository.save(gl);

            touchSupplierProduct(po.getSupplierId(), pol.getItemId(), unitCost);
        }
        sb.setItemCount(itemCount);
        sb.setTotalInitialQuantity(totalInitial);
        sb.setTotalRemainingQuantity(totalRemaining);
        supplyBatchRepository.save(sb);

        LocalDate entryDate = LocalDate.ofInstant(grn.getReceivedAt(), ZoneOffset.UTC);
        JournalEntry entry = new JournalEntry();
        entry.setBusinessId(businessId);
        entry.setEntryDate(entryDate);
        entry.setSourceType(PurchasingConstants.JOURNAL_SOURCE_PATH_A_GRN);
        entry.setSourceId(grn.getId());
        entry.setMemo("Path A GRN " + grn.getId());
        BigDecimal grniScaled = grniTotal.setScale(2, RoundingMode.HALF_UP);
        if (grniScaled.signum() > 0) {
            entry.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.INVENTORY), grniScaled);
            entry.credit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.GOODS_RECEIVED_NOT_INVOICED), grniScaled);
        }
        String jeId = ledgerPostingPort.post(entry);

        grn.setGrniAmount(grniScaled);
        grn.setStatus(PurchasingConstants.GRN_POSTED);
        goodsReceiptRepository.save(grn);

        return new PostGoodsReceiptResponse(grn.getId(), grniScaled, createdLines.size());
    }

    private static void validateGrnLineIds(PostGoodsReceiptRequest req) {
        Set<String> seen = new HashSet<>();
        for (PostGoodsReceiptLineInput l : req.lines()) {
            if (!seen.add(l.purchaseOrderLineId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate purchase order line in receipt");
            }
        }
    }

    @Transactional
    public PostGrnSupplierInvoiceResponse postSupplierInvoiceForGrn(
            String businessId,
            String goodsReceiptId,
            PostGrnSupplierInvoiceRequest req,
            String idemKey
    ) {
        if (idemKey != null && !idemKey.isBlank()) {
            return postInvoiceWithIdempotency(businessId, goodsReceiptId, req, idemKey.trim());
        }
        return executePostGrnInvoice(businessId, goodsReceiptId, req);
    }

    private PostGrnSupplierInvoiceResponse postInvoiceWithIdempotency(
            String businessId,
            String goodsReceiptId,
            PostGrnSupplierInvoiceRequest req,
            String keyRaw
    ) {
        String route = postGrnInvoiceRoute(goodsReceiptId);
        String keyHash = TokenHasher.sha256Hex(keyRaw);
        synchronized ((businessId + "|" + route + "|" + keyHash).intern()) {
            String bodyJson;
            try {
                bodyJson = objectMapper.writeValueAsString(req);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            String bodyHash = TokenHasher.sha256Hex(bodyJson);
            Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByBusinessIdAndKeyHashAndRoute(
                    businessId, keyHash, route);
            if (existing.isPresent()) {
                IdempotencyKey row = existing.get();
                if (!row.getBodyHash().equals(bodyHash)) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT, "Idempotency key already used with a different request body");
                }
                try {
                    return objectMapper.readValue(row.getResponseJson(), PostGrnSupplierInvoiceResponse.class);
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }
            PostGrnSupplierInvoiceResponse response = executePostGrnInvoice(businessId, goodsReceiptId, req);
            persistInvoiceIdem(businessId, keyHash, bodyHash, route, response);
            return response;
        }
    }

    private void persistInvoiceIdem(
            String businessId,
            String keyHash,
            String bodyHash,
            String route,
            PostGrnSupplierInvoiceResponse response
    ) {
        String json;
        try {
            json = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        IdempotencyKey row = new IdempotencyKey();
        row.setBusinessId(businessId);
        row.setKeyHash(keyHash);
        row.setRoute(route);
        row.setBodyHash(bodyHash);
        row.setHttpStatus(HttpStatus.OK.value());
        row.setResponseJson(json);
        try {
            idempotencyKeyRepository.save(row);
        } catch (DataIntegrityViolationException e) {
            IdempotencyKey replay = idempotencyKeyRepository
                    .findByBusinessIdAndKeyHashAndRoute(businessId, keyHash, route)
                    .orElseThrow(() -> e);
            if (!replay.getBodyHash().equals(bodyHash)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Idempotency key already used with a different request body");
            }
        }
    }

    private PostGrnSupplierInvoiceResponse executePostGrnInvoice(
            String businessId,
            String goodsReceiptId,
            PostGrnSupplierInvoiceRequest req
    ) {
        GoodsReceipt grn = goodsReceiptRepository.findByIdAndBusinessId(goodsReceiptId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Goods receipt not found"));
        if (!PurchasingConstants.GRN_POSTED.equals(grn.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Goods receipt is not posted");
        }
        if (supplierInvoiceRepository.existsByGoodsReceiptId(grn.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Supplier invoice already exists for this receipt");
        }
        PurchaseOrder po = loadPo(businessId, grn.getPurchaseOrderId());

        List<GoodsReceiptLine> grnLines = goodsReceiptLineRepository.findByGoodsReceiptIdOrderByIdAsc(grn.getId());
        if (grnLines.size() != req.lines().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice lines must match goods receipt lines");
        }
        for (int i = 0; i < grnLines.size(); i++) {
            GoodsReceiptLine gl = grnLines.get(i);
            PostGrnSupplierInvoiceLineInput il = req.lines().get(i);
            PurchaseOrderLine pol = purchaseOrderLineRepository.findById(gl.getPurchaseOrderLineId()).orElseThrow();
            if (!pol.getItemId().equals(il.itemId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice line item does not match receipt");
            }
            if (gl.getQtyReceived().compareTo(il.qty()) != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice quantity does not match receipt");
            }
            BigDecimal expect = il.qty().multiply(il.unitCost()).setScale(2, RoundingMode.HALF_UP);
            if (il.lineTotal().setScale(2, RoundingMode.HALF_UP).compareTo(expect) != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line total does not match qty × unit cost");
            }
        }

        BigDecimal invoiceSum = req.lines().stream()
                .map(PostGrnSupplierInvoiceLineInput::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        String mode = threeWayMatchMode(businessId);
        BigDecimal grni = grn.getGrniAmount().setScale(2, RoundingMode.HALF_UP);
        if (PurchasingConstants.THREE_WAY_BLOCK.equals(mode)) {
            if (invoiceSum.subtract(grni).abs().compareTo(MONEY_SCALE) > 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Invoice total does not match goods receipt (3-way match)");
            }
        }

        LedgerAccountResolver lar = ledgerAccountResolver;

        LocalDate invoiceDate = req.invoiceDate();
        LocalDate due = req.dueDate() != null ? req.dueDate() : invoiceDate.plusDays(30);

        SupplierInvoice inv = new SupplierInvoice();
        inv.setBusinessId(businessId);
        inv.setSupplierId(po.getSupplierId());
        inv.setGoodsReceiptId(grn.getId());
        inv.setInvoiceNumber(req.invoiceNumber().trim());
        inv.setInvoiceDate(invoiceDate);
        inv.setDueDate(due);
        inv.setSubtotal(invoiceSum);
        inv.setTaxTotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        inv.setGrandTotal(invoiceSum);
        inv.setStatus(PurchasingConstants.INVOICE_POSTED);
        try {
            supplierInvoiceRepository.save(inv);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invoice number already exists");
        }

        int sort = 0;
        for (PostGrnSupplierInvoiceLineInput il : req.lines()) {
            SupplierInvoiceLine sil = new SupplierInvoiceLine();
            sil.setInvoiceId(inv.getId());
            sil.setDescription("Path A — " + il.itemId());
            sil.setItemId(il.itemId());
            sil.setQty(il.qty());
            sil.setUnitCost(il.unitCost());
            sil.setLineTotal(il.lineTotal().setScale(2, RoundingMode.HALF_UP));
            sil.setSortOrder(sort++);
            supplierInvoiceLineRepository.save(sil);
        }

        JournalEntry entry = new JournalEntry();
        entry.setBusinessId(businessId);
        entry.setEntryDate(invoiceDate);
        entry.setSourceType(PurchasingConstants.JOURNAL_SOURCE_PATH_A_INVOICE);
        entry.setSourceId(inv.getId());
        entry.setMemo("Path A supplier invoice " + inv.getInvoiceNumber());
        BigDecimal ap = invoiceSum;
        BigDecimal diff = ap.subtract(grni);
        entry.debit(lar.resolveId(businessId, LedgerAccountCodes.GOODS_RECEIVED_NOT_INVOICED), grni);
        if (diff.compareTo(MONEY_SCALE) > 0) {
            entry.debit(lar.resolveId(businessId, LedgerAccountCodes.PURCHASE_PRICE_VARIANCE), diff);
        } else if (diff.compareTo(MONEY_SCALE.negate()) < 0) {
            entry.credit(lar.resolveId(businessId, LedgerAccountCodes.PURCHASE_PRICE_VARIANCE), diff.negate());
        }
        entry.credit(lar.resolveId(businessId, LedgerAccountCodes.ACCOUNTS_PAYABLE), ap);
        String jeId = ledgerPostingPort.post(entry);

        return new PostGrnSupplierInvoiceResponse(inv.getId(), inv.getInvoiceNumber(), jeId, ap);
    }

    private String threeWayMatchMode(String businessId) {
        String raw = businessRepository.findSettingsJsonById(businessId).orElse("{}");
        try {
            JsonNode n = objectMapper.readTree(raw.isBlank() ? "{}" : raw);
            if (n.isTextual()) {
                n = objectMapper.readTree(n.asText());
            }
            String v = n.path("threeWayMatchMode").asText(PurchasingConstants.THREE_WAY_OFF);
            if (v == null || v.isBlank()) {
                return PurchasingConstants.THREE_WAY_OFF;
            }
            return v.trim().toLowerCase();
        } catch (JsonProcessingException e) {
            return PurchasingConstants.THREE_WAY_OFF;
        }
    }

    private void touchSupplierProduct(String supplierId, String itemId, BigDecimal unitCost) {
        Optional<SupplierProduct> opt = supplierProductRepository.findBySupplierIdAndItemId(supplierId, itemId);
        opt.ifPresent(sp -> {
            if (sp.getDeletedAt() != null || !sp.isActive()) {
                return;
            }
            sp.setLastCostPrice(unitCost);
            sp.setLastPurchaseAt(Instant.now());
            supplierProductRepository.save(sp);
        });
    }



    private PurchaseOrder loadPo(String businessId, String id) {
        return purchaseOrderRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase order not found"));
    }

    private static void assertPoDraft(PurchaseOrder po) {
        if (!PurchasingConstants.PO_DRAFT.equals(po.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Purchase order is not editable");
        }
    }

    private void assertSupplierInBusiness(String businessId, String supplierId) {
        supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supplier not found"));
    }

    private void assertBranchInBusiness(String businessId, String branchId) {
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
    }

    private PathAPurchaseOrderDetailResponse detailOf(PurchaseOrder po) {
        List<PathAPurchaseOrderLineResponse> lines = purchaseOrderLineRepository
                .findByPurchaseOrderIdOrderBySortOrderAscIdAsc(po.getId()).stream()
                .map(PathAPurchaseService::lineResponse)
                .toList();
        return new PathAPurchaseOrderDetailResponse(
                po.getId(),
                po.getSupplierId(),
                po.getBranchId(),
                po.getPoNumber(),
                po.getExpectedDate(),
                po.getStatus(),
                po.getNotes(),
                lines
        );
    }

    private static PathAPurchaseOrderLineResponse lineResponse(PurchaseOrderLine l) {
        return new PathAPurchaseOrderLineResponse(
                l.getId(),
                l.getSortOrder(),
                l.getItemId(),
                l.getQtyOrdered(),
                l.getQtyReceived(),
                l.getUnitEstimatedCost()
        );
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
