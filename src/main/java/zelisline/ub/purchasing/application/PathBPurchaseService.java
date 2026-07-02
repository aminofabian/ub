package zelisline.ub.purchasing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.inventory.application.BatchNumberGenerator;
import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.IdempotencyKey;
import zelisline.ub.catalog.repository.IdempotencyKeyRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerAccountResolver;
import zelisline.ub.finance.application.LedgerPostingPort;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.inventory.WastageReason;
import zelisline.ub.inventory.domain.SupplyBatch;
import zelisline.ub.inventory.repository.SupplyBatchRepository;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.api.dto.AddPathBLineRequest;
import zelisline.ub.purchasing.api.dto.CreatePathBSessionRequest;
import zelisline.ub.purchasing.api.dto.PathBLineResponse;
import zelisline.ub.purchasing.api.dto.PathBSessionDetailResponse;
import zelisline.ub.purchasing.api.dto.PathBSessionListRow;
import zelisline.ub.purchasing.api.dto.PostPathBLineBreakdown;
import zelisline.ub.purchasing.api.dto.PostPathBRequest;
import zelisline.ub.purchasing.api.dto.PostPathBResponse;
import zelisline.ub.purchasing.api.dto.PatchPathBSupplyInvoiceLineRequest;
import zelisline.ub.purchasing.domain.InventoryBatch;
import zelisline.ub.purchasing.domain.RawPurchaseLine;
import zelisline.ub.purchasing.domain.RawPurchaseSession;
import zelisline.ub.purchasing.domain.StockMovement;
import zelisline.ub.purchasing.domain.SupplierInvoice;
import zelisline.ub.purchasing.domain.SupplierInvoiceLine;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.purchasing.repository.RawPurchaseLineRepository;
import zelisline.ub.purchasing.repository.RawPurchaseSessionRepository;
import zelisline.ub.purchasing.repository.StockMovementRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceLineRepository;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.purchasing.repository.SupplierPaymentAllocationRepository;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class PathBPurchaseService {

    private static final BigDecimal MONEY_SCALE = new BigDecimal("0.01");
    private static final int UNIT_SCALE = 4;

    private final BatchNumberGenerator batchNumberGenerator;

    private final RawPurchaseSessionRepository sessionRepository;
    private final RawPurchaseLineRepository lineRepository;
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
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;
    private final PackageVariantStockResolver packageVariantStockResolver;
    private final PurchaseUnitConversionService purchaseUnitConversionService;
    private final SupplierPaymentAllocationRepository allocationRepository;
    private final SupplyBatchRepository supplyBatchRepository;

    public static String postRoute(String sessionId) {
        return "POST /api/v1/purchasing/path-b/sessions/%s/post".formatted(sessionId);
    }

    @Transactional
    public PathBSessionDetailResponse createSession(String businessId, CreatePathBSessionRequest req) {
        assertSupplierInBusiness(businessId, req.supplierId());
        assertBranchInBusiness(businessId, req.branchId());
        RawPurchaseSession s = new RawPurchaseSession();
        s.setBusinessId(businessId);
        s.setSupplierId(req.supplierId());
        s.setBranchId(req.branchId());
        s.setReceivedAt(req.receivedAt());
        s.setNotes(blankToNull(req.notes()));
        s.setStatus(PurchasingConstants.SESSION_DRAFT);
        sessionRepository.save(s);
        return detailOf(s);
    }

    @Transactional(readOnly = true)
    public PathBSessionDetailResponse getSession(String businessId, String sessionId) {
        RawPurchaseSession s = loadSession(businessId, sessionId);
        return detailOf(s);
    }

    @Transactional(readOnly = true)
    public List<PathBSessionListRow> listSessions(String businessId, String supplierId, String status) {
        String effectiveStatus = status != null && !status.isBlank() ? status.trim() : PurchasingConstants.SESSION_DRAFT;
        List<RawPurchaseSession> sessions = supplierId != null && !supplierId.isBlank()
                ? sessionRepository.findByBusinessIdAndSupplierIdAndStatusOrderByCreatedAtDesc(
                        businessId, supplierId.trim(), effectiveStatus)
                : sessionRepository.findByBusinessIdAndStatusOrderByCreatedAtDesc(businessId, effectiveStatus);
        return sessions.stream().map(this::toListRow).toList();
    }

    private PathBSessionListRow toListRow(RawPurchaseSession s) {
        List<RawPurchaseLine> lines = lineRepository.findBySessionIdOrderBySortOrderAscIdAsc(s.getId());
        BigDecimal total = lines.stream()
                .map(RawPurchaseLine::getAmountMoney)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        return new PathBSessionListRow(
                s.getId(),
                s.getSupplierId(),
                s.getBranchId(),
                s.getReceivedAt(),
                s.getStatus(),
                lines.size(),
                total
        );
    }

    @Transactional
    public PathBLineResponse addLine(String businessId, String sessionId, AddPathBLineRequest req) {
        RawPurchaseSession s = loadSession(businessId, sessionId);
        assertDraft(s);
        RawPurchaseLine line = new RawPurchaseLine();
        line.setSessionId(s.getId());
        line.setSortOrder(lineRepository.maxSortOrder(sessionId) + 1);
        line.setDescriptionText(req.description().trim());
        line.setAmountMoney(req.amountMoney().setScale(2, RoundingMode.HALF_UP));
        line.setSuggestedItemId(blankToNull(req.suggestedItemId()));
        line.setLineStatus(PurchasingConstants.LINE_PENDING);
        lineRepository.save(line);
        return toLineResponse(line);
    }

    @Transactional
    public PathBLineResponse patchLine(
            String businessId,
            String sessionId,
            String lineId,
            AddPathBLineRequest req
    ) {
        RawPurchaseSession s = loadSession(businessId, sessionId);
        assertDraft(s);
        RawPurchaseLine line = lineRepository.findById(lineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found"));
        if (!sessionId.equals(line.getSessionId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line does not belong to session");
        }
        if (!PurchasingConstants.LINE_PENDING.equals(line.getLineStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Line cannot be edited");
        }
        line.setDescriptionText(req.description().trim());
        line.setAmountMoney(req.amountMoney().setScale(2, RoundingMode.HALF_UP));
        line.setSuggestedItemId(blankToNull(req.suggestedItemId()));
        lineRepository.save(line);
        return toLineResponse(line);
    }

    @Transactional
    public void deleteLine(String businessId, String sessionId, String lineId) {
        RawPurchaseSession s = loadSession(businessId, sessionId);
        assertDraft(s);
        RawPurchaseLine line = lineRepository.findById(lineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found"));
        if (!sessionId.equals(line.getSessionId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line does not belong to session");
        }
        if (!PurchasingConstants.LINE_PENDING.equals(line.getLineStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Line cannot be deleted");
        }
        lineRepository.delete(line);
    }

    @Transactional
    public PostPathBResponse postSession(String businessId, String sessionId, PostPathBRequest req, String idemKey) {
        if (idemKey != null && !idemKey.isBlank()) {
            return postWithIdempotency(businessId, sessionId, req, idemKey.trim());
        }
        return executePost(businessId, sessionId, req);
    }

    private PostPathBResponse postWithIdempotency(String businessId, String sessionId, PostPathBRequest req, String keyRaw) {
        String route = postRoute(sessionId);
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
                    return objectMapper.readValue(row.getResponseJson(), PostPathBResponse.class);
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }
            PostPathBResponse response = executePost(businessId, sessionId, req);
            persistIdempotency(businessId, keyHash, bodyHash, route, response);
            return response;
        }
    }

    private void persistIdempotency(
            String businessId,
            String keyHash,
            String bodyHash,
            String route,
            PostPathBResponse response
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

    private PostPathBResponse executePost(String businessId, String sessionId, PostPathBRequest req) {
        RawPurchaseSession session = loadSession(businessId, sessionId);
        assertDraft(session);
        List<RawPurchaseLine> dbLines = lineRepository.findBySessionIdOrderBySortOrderAscIdAsc(sessionId);
        if (dbLines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session has no lines");
        }
        validateBreakdown(dbLines, req);
        LedgerAccountResolver lar = ledgerAccountResolver; // local alias for brevity

        Map<String, PostPathBLineBreakdown> breakdownByLineId = req.lines().stream()
                .collect(Collectors.toMap(PostPathBLineBreakdown::lineId, b -> b));
        List<RawPurchaseLine> postedLines = new ArrayList<>();
        BigDecimal sumInv = BigDecimal.ZERO;
        BigDecimal sumWaste = BigDecimal.ZERO;
        BigDecimal apTotal = BigDecimal.ZERO;
        List<LinePostPlan> plans = new ArrayList<>();
        for (RawPurchaseLine line : dbLines) {
            PostPathBLineBreakdown br = breakdownByLineId.get(line.getId());
            if (br == null) {
                continue;
            }
            postedLines.add(line);
            Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(br.itemId(), businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
            purchaseUnitConversionService.assertMatchesPosted(
                    businessId,
                    session.getSupplierId(),
                    br.itemId(),
                    br.usableQty(),
                    br.purchaseQty(),
                    br.purchaseUnit()
            );
            CostSplit split = splitLine(line.getAmountMoney(), br.usableQty(), br.wastageQty());
            plans.add(new LinePostPlan(line, item, br.itemId(), br.usableQty(), br.wastageQty(), split, br.expiryDate()));
            sumInv = sumInv.add(split.inventoryMoney());
            sumWaste = sumWaste.add(split.wastageMoney());
            apTotal = apTotal.add(line.getAmountMoney());
        }
        if (postedLines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No lines selected to receive");
        }
        if (sumInv.add(sumWaste).compareTo(apTotal) != 0) {
            throw new IllegalStateException("Journal allocation rounding drift");
        }

        SupplyBatch sb = new SupplyBatch();
        sb.setBusinessId(businessId);
        sb.setBranchId(session.getBranchId());
        sb.setSupplierId(session.getSupplierId());
        sb.setBatchNumber(batchNumberGenerator.next(session.getSupplierId(), supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(session.getSupplierId(), businessId).map(zelisline.ub.suppliers.domain.Supplier::getName).orElse(null), session.getReceivedAt(), businessId));
        sb.setBatchName(null);
        sb.setSourceType(PurchasingConstants.BATCH_SOURCE_PATH_B);
        sb.setSourceId(session.getId());
        sb.setItemCount(0);
        sb.setTotalInitialQuantity(BigDecimal.ZERO);
        sb.setTotalRemainingQuantity(BigDecimal.ZERO);
        sb.setReceivedAt(session.getReceivedAt());
        sb.setStatus("active");
        supplyBatchRepository.save(sb);

        int itemCount = 0;
        BigDecimal totalInitial = BigDecimal.ZERO;
        BigDecimal totalRemaining = BigDecimal.ZERO;
        for (LinePostPlan plan : plans) {
            applyLinePost(businessId, session, plan, sb);
            if (plan.usableQty().signum() > 0) {
                itemCount++;
                totalInitial = totalInitial.add(plan.usableQty());
                totalRemaining = totalRemaining.add(plan.usableQty());
            }
        }
        sb.setItemCount(itemCount);
        sb.setTotalInitialQuantity(totalInitial);
        sb.setTotalRemainingQuantity(totalRemaining);
        supplyBatchRepository.save(sb);

        LocalDate invoiceDate = LocalDate.ofInstant(session.getReceivedAt(), ZoneOffset.UTC);
        String invoiceNumber = "PB-" + session.getId().replace("-", "").substring(0, 8).toUpperCase();
        SupplierInvoice inv = new SupplierInvoice();
        inv.setBusinessId(businessId);
        inv.setSupplierId(session.getSupplierId());
        inv.setRawPurchaseSessionId(session.getId());
        inv.setInvoiceNumber(invoiceNumber);
        inv.setInvoiceDate(invoiceDate);
        inv.setDueDate(invoiceDate.plusDays(30));
        inv.setSubtotal(apTotal.setScale(2, RoundingMode.HALF_UP));
        inv.setTaxTotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        inv.setGrandTotal(apTotal.setScale(2, RoundingMode.HALF_UP));
        inv.setStatus(PurchasingConstants.INVOICE_POSTED);
        supplierInvoiceRepository.save(inv);

        int sort = 0;
        for (RawPurchaseLine line : postedLines) {
            BigDecimal qty = line.getUsableQty().add(line.getWastageQty());
            BigDecimal unit = line.getAmountMoney().divide(qty, UNIT_SCALE, RoundingMode.HALF_UP);
            SupplierInvoiceLine sil = new SupplierInvoiceLine();
            sil.setInvoiceId(inv.getId());
            sil.setDescription(line.getDescriptionText());
            sil.setItemId(line.getPostedItemId());
            sil.setQty(qty);
            sil.setUnitCost(unit);
            sil.setLineTotal(line.getAmountMoney().setScale(2, RoundingMode.HALF_UP));
            sil.setSortOrder(sort++);
            sil.setRawLineId(line.getId());
            supplierInvoiceLineRepository.save(sil);
        }

        JournalEntry entry = new JournalEntry();
        entry.setBusinessId(businessId);
        entry.setEntryDate(invoiceDate);
        entry.setSourceType(PurchasingConstants.JOURNAL_SOURCE_PATH_B);
        entry.setSourceId(session.getId());
        entry.setMemo("Path B purchase " + invoiceNumber);
        BigDecimal sumInv2 = sumInv.setScale(2, RoundingMode.HALF_UP);
        BigDecimal sumWaste2 = sumWaste.setScale(2, RoundingMode.HALF_UP);
        BigDecimal ap2 = apTotal.setScale(2, RoundingMode.HALF_UP);
        if (sumInv2.signum() > 0) {
            entry.debit(lar.resolveId(businessId, LedgerAccountCodes.INVENTORY), sumInv2);
        }
        if (sumWaste2.signum() > 0) {
            entry.debit(lar.resolveId(businessId, LedgerAccountCodes.INVENTORY_SHRINKAGE), sumWaste2);
        }
        entry.credit(lar.resolveId(businessId, LedgerAccountCodes.ACCOUNTS_PAYABLE), ap2);
        String jeId = ledgerPostingPort.post(entry);

        int pendingRemaining = lineRepository.countBySessionIdAndLineStatus(sessionId, PurchasingConstants.LINE_PENDING);
        String sessionStatus = pendingRemaining == 0 ? PurchasingConstants.SESSION_POSTED : PurchasingConstants.SESSION_DRAFT;
        session.setStatus(sessionStatus);
        sessionRepository.save(session);

        return new PostPathBResponse(session.getId(), sessionStatus, inv.getId(), invoiceNumber, jeId, ap2, postedLines.size(), sb.getId());
    }



    private void applyLinePost(String businessId, RawPurchaseSession session, LinePostPlan p, SupplyBatch sb) {
        RawPurchaseLine line = p.line();
        CostSplit s = p.split();
        if (p.usableQty().signum() > 0) {
            PackageVariantStockResolver.StockPickResolution inbound = packageVariantStockResolver.resolveInbound(
                    businessId, p.itemId(), p.usableQty());
            Item holder = packageVariantStockResolver.requireInventoryHolder(businessId, inbound.stockItemId());
            BigDecimal unitCost = s.inventoryMoney().divide(inbound.stockQuantity(), UNIT_SCALE, RoundingMode.HALF_UP);
            InventoryBatch b = new InventoryBatch();
            b.setBusinessId(businessId);
            b.setBranchId(session.getBranchId());
            b.setItemId(inbound.stockItemId());
            b.setSupplyBatchId(sb.getId());
            b.setSupplierId(session.getSupplierId());
            b.setBatchNumber("B-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            b.setSourceType(PurchasingConstants.BATCH_SOURCE_PATH_B);
            b.setSourceId(line.getId());
            b.setInitialQuantity(inbound.stockQuantity());
            b.setQuantityRemaining(inbound.stockQuantity());
            b.setUnitCost(unitCost);
            b.setReceivedAt(session.getReceivedAt());
            if (p.expiryDate() != null) {
                b.setExpiryDate(p.expiryDate());
            }
            inventoryBatchRepository.save(b);

            StockMovement sm = new StockMovement();
            sm.setBusinessId(businessId);
            sm.setBranchId(session.getBranchId());
            sm.setItemId(inbound.stockItemId());
            sm.setBatchId(b.getId());
            sm.setMovementType(PurchasingConstants.MOVEMENT_RECEIPT);
            sm.setReferenceType(PurchasingConstants.STOCK_REF_RAW_LINE);
            sm.setReferenceId(line.getId());
            sm.setQuantityDelta(inbound.stockQuantity());
            sm.setUnitCost(unitCost);
            stockMovementRepository.save(sm);

            BigDecimal base = holder.getCurrentStock() == null ? BigDecimal.ZERO : holder.getCurrentStock();
            holder.setCurrentStock(base.add(inbound.stockQuantity()));
            itemRepository.save(holder);

            line.setInventoryBatchId(b.getId());
            touchSupplierProduct(session.getSupplierId(), p.itemId(), unitCost);
        }
        if (p.wastageQty().signum() > 0) {
            BigDecimal wUnit = s.wastageMoney().divide(p.wastageQty(), UNIT_SCALE, RoundingMode.HALF_UP);

            // ── Create a wastage batch to hold the "wasted" stock record ──
            InventoryBatch wasteBatch = new InventoryBatch();
            wasteBatch.setBusinessId(businessId);
            wasteBatch.setBranchId(session.getBranchId());
            wasteBatch.setItemId(p.itemId());
            wasteBatch.setSupplierId(session.getSupplierId());
            wasteBatch.setBatchNumber("W-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            wasteBatch.setSourceType("path_b_wastage");
            wasteBatch.setSourceId(line.getId());
            wasteBatch.setInitialQuantity(p.wastageQty());
            wasteBatch.setQuantityRemaining(BigDecimal.ZERO);  // fully depleted
            wasteBatch.setUnitCost(wUnit);
            wasteBatch.setReceivedAt(session.getReceivedAt());
            wasteBatch.setStatus("depleted");                  // not pickable
            inventoryBatchRepository.save(wasteBatch);

            // ── Record movement linked to the wastage batch ───────────────
            StockMovement wm = new StockMovement();
            wm.setBusinessId(businessId);
            wm.setBranchId(session.getBranchId());
            wm.setItemId(p.itemId());
            wm.setBatchId(wasteBatch.getId());
            wm.setMovementType(PurchasingConstants.MOVEMENT_WASTAGE);
            wm.setReferenceType(PurchasingConstants.STOCK_REF_RAW_LINE);
            wm.setReferenceId(line.getId());
            wm.setQuantityDelta(p.wastageQty().negate());
            wm.setUnitCost(wUnit);
            wm.setReason(WastageReason.SPOILAGE.name() + " — Path B breakdown");
            wm.setWastageReason(WastageReason.SPOILAGE.name());
            stockMovementRepository.save(wm);
        }
        line.setPostedItemId(p.itemId());
        line.setUsableQty(p.usableQty());
        line.setWastageQty(p.wastageQty());
        line.setLineStatus(PurchasingConstants.LINE_POSTED);
        lineRepository.save(line);
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

    private static void validateBreakdown(List<RawPurchaseLine> dbLines, PostPathBRequest req) {
        if (req.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Breakdown must include at least one line");
        }
        Map<String, RawPurchaseLine> dbById = dbLines.stream()
                .collect(Collectors.toMap(RawPurchaseLine::getId, l -> l));
        Set<String> seenLineIds = new HashSet<>();
        Set<String> seenItemIds = new HashSet<>();
        for (PostPathBLineBreakdown b : req.lines()) {
            RawPurchaseLine dbLine = dbById.get(b.lineId());
            if (dbLine == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown line id");
            }
            if (PurchasingConstants.LINE_POSTED.equals(dbLine.getLineStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Line " + b.lineId() + " has already been received");
            }
            if (!seenLineIds.add(b.lineId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate line in breakdown");
            }
            if (b.usableQty().signum() <= 0 && b.wastageQty().signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each line needs usable or wastage quantity");
            }
            if (!seenItemIds.add(b.itemId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Duplicate item on multiple lines — combine quantities or use a separate receipt");
            }
        }
    }

    private record CostSplit(BigDecimal inventoryMoney, BigDecimal wastageMoney) {
    }

    private static CostSplit splitLine(BigDecimal lineAmount, BigDecimal usable, BigDecimal wastage) {
        BigDecimal q = usable.add(wastage);
        BigDecimal unit = lineAmount.divide(q, 6, RoundingMode.HALF_UP);
        BigDecimal invMoney = unit.multiply(usable).setScale(2, RoundingMode.HALF_UP);
        BigDecimal wasteMoney = lineAmount.subtract(invMoney);
        return new CostSplit(invMoney, wasteMoney);
    }

    private record LinePostPlan(
            RawPurchaseLine line,
            Item item,
            String itemId,
            BigDecimal usableQty,
            BigDecimal wastageQty,
            CostSplit split,
            LocalDate expiryDate
    ) {
    }

    private RawPurchaseSession loadSession(String businessId, String sessionId) {
        return sessionRepository.findByIdAndBusinessId(sessionId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
    }

    private static void assertDraft(RawPurchaseSession s) {
        if (!PurchasingConstants.SESSION_DRAFT.equals(s.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is not editable");
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

    private PathBSessionDetailResponse detailOf(RawPurchaseSession s) {
        List<PathBLineResponse> lines = lineRepository.findBySessionIdOrderBySortOrderAscIdAsc(s.getId()).stream()
                .map(PathBPurchaseService::toLineResponse)
                .toList();
        return new PathBSessionDetailResponse(
                s.getId(),
                s.getSupplierId(),
                s.getBranchId(),
                s.getReceivedAt(),
                s.getNotes(),
                s.getStatus(),
                lines
        );
    }

    private static PathBLineResponse toLineResponse(RawPurchaseLine l) {
        return new PathBLineResponse(
                l.getId(),
                l.getSortOrder(),
                l.getDescriptionText(),
                l.getAmountMoney(),
                l.getSuggestedItemId(),
                l.getLineStatus()
        );
    }

    /**
     * Reverse inventory effect for a posted Path B line (becomes {@link PurchasingConstants#LINE_PENDING}).
     */
    public void reversePostedPathBLine(String businessId, RawPurchaseLine line) {
        if (!PurchasingConstants.LINE_POSTED.equals(line.getLineStatus())) {
            return;
        }
        List<StockMovement> moves = stockMovementRepository.findByBusinessIdAndReferenceTypeAndReferenceId(
                businessId,
                PurchasingConstants.STOCK_REF_RAW_LINE,
                line.getId()
        );
        for (StockMovement sm : moves) {
            Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(sm.getItemId(), businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
            if (PurchasingConstants.MOVEMENT_RECEIPT.equals(sm.getMovementType())) {
                BigDecimal delta = sm.getQuantityDelta();
                BigDecimal base = item.getCurrentStock() == null ? BigDecimal.ZERO : item.getCurrentStock();
                item.setCurrentStock(base.subtract(delta));
                itemRepository.save(item);
            }
            stockMovementRepository.delete(sm);
        }
        if (line.getInventoryBatchId() != null) {
            inventoryBatchRepository.findByIdAndBusinessId(line.getInventoryBatchId(), businessId)
                    .ifPresent(inventoryBatchRepository::delete);
        }
        line.setInventoryBatchId(null);
        line.setPostedItemId(null);
        line.setUsableQty(null);
        line.setWastageQty(null);
        line.setLineStatus(PurchasingConstants.LINE_PENDING);
        lineRepository.save(line);
    }

    public void reapplyPostedPathBLine(
            String businessId,
            RawPurchaseSession session,
            RawPurchaseLine line,
            String itemId,
            BigDecimal usableQty,
            BigDecimal wastageQty,
            LocalDate expiryDate
    ) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
        if (usableQty.signum() <= 0 && wastageQty.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each line needs usable or wastage quantity");
        }
        CostSplit split = splitLine(line.getAmountMoney(), usableQty, wastageQty);
        LinePostPlan p = new LinePostPlan(line, item, itemId, usableQty, wastageQty, split, expiryDate);
        SupplyBatch sb = supplyBatchRepository
                .findByBusinessIdAndSourceTypeAndSourceId(businessId, PurchasingConstants.BATCH_SOURCE_PATH_B, session.getId())
                .orElseGet(() -> {
                    SupplyBatch fresh = new SupplyBatch();
                    fresh.setBusinessId(businessId);
                    fresh.setBranchId(session.getBranchId());
                    fresh.setSupplierId(session.getSupplierId());
                    fresh.setBatchNumber(batchNumberGenerator.next(session.getSupplierId(), supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(session.getSupplierId(), businessId).map(zelisline.ub.suppliers.domain.Supplier::getName).orElse(null), session.getReceivedAt(), businessId));
                    fresh.setBatchName(null);
                    fresh.setSourceType(PurchasingConstants.BATCH_SOURCE_PATH_B);
                    fresh.setSourceId(session.getId());
                    fresh.setItemCount(0);
                    fresh.setTotalInitialQuantity(BigDecimal.ZERO);
                    fresh.setTotalRemainingQuantity(BigDecimal.ZERO);
                    fresh.setReceivedAt(session.getReceivedAt());
                    fresh.setStatus("active");
                    return supplyBatchRepository.save(fresh);
                });
        applyLinePost(businessId, session, p, sb);
    }

    @Transactional
    public void rebalancePostedSupplyInvoiceLines(
            String businessId,
            String invoiceId,
            List<PatchPathBSupplyInvoiceLineRequest> lineInputs
    ) {
        SupplierInvoice inv = supplierInvoiceRepository.findByIdAndBusinessId(invoiceId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        if (inv.getRawPurchaseSessionId() == null || inv.getRawPurchaseSessionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a Path B invoice");
        }
        BigDecimal paid = allocationRepository.sumAmountBySupplierInvoiceId(invoiceId);
        if (paid != null && paid.compareTo(MONEY_SCALE) >= 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cannot edit line quantities or amounts while the invoice has payments");
        }
        String sessionId = inv.getRawPurchaseSessionId();
        RawPurchaseSession session = sessionRepository.findByIdAndBusinessId(sessionId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Purchase session not found"));
        if (!PurchasingConstants.SESSION_POSTED.equals(session.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session is not posted");
        }
        List<SupplierInvoiceLine> sils = supplierInvoiceLineRepository.findByInvoiceIdOrderBySortOrderAsc(invoiceId);
        if (lineInputs.size() != sils.size()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Provide exactly one entry per invoice line (expected " + sils.size() + ")");
        }
        Map<String, PatchPathBSupplyInvoiceLineRequest> byId = new HashMap<>();
        for (PatchPathBSupplyInvoiceLineRequest in : lineInputs) {
            if (byId.put(in.supplierInvoiceLineId(), in) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate supplier invoice line id");
            }
        }
        for (SupplierInvoiceLine sil : sils) {
            if (!byId.containsKey(sil.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing line " + sil.getId());
            }
            if (sil.getRawLineId() == null || sil.getRawLineId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invoice line is not linked to a receipt");
            }
        }
        Map<String, LocalDate> expiryByRawLine = new HashMap<>();
        for (SupplierInvoiceLine sil : sils) {
            RawPurchaseLine rl = lineRepository.findById(sil.getRawLineId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Raw line not found"));
            if (rl.getInventoryBatchId() != null) {
                inventoryBatchRepository.findByIdAndBusinessId(rl.getInventoryBatchId(), businessId)
                        .ifPresent(b -> expiryByRawLine.put(rl.getId(), b.getExpiryDate()));
            }
        }
        for (SupplierInvoiceLine sil : sils) {
            RawPurchaseLine rl = lineRepository.findById(sil.getRawLineId()).orElseThrow();
            reversePostedPathBLine(businessId, rl);
        }
        BigDecimal sumTotals = BigDecimal.ZERO;
        for (SupplierInvoiceLine sil : sils) {
            PatchPathBSupplyInvoiceLineRequest in = byId.get(sil.getId());
            RawPurchaseLine rl = lineRepository.findById(sil.getRawLineId()).orElseThrow();
            BigDecimal lineTotal = in.lineTotal().setScale(2, RoundingMode.HALF_UP);
            BigDecimal usable = in.usableQty().setScale(4, RoundingMode.HALF_UP);
            BigDecimal wastage = in.wastageQty().setScale(4, RoundingMode.HALF_UP);
            if (usable.signum() <= 0 && wastage.signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each line needs usable or wastage quantity");
            }
            rl.setAmountMoney(lineTotal);
            if (in.description() != null && !in.description().isBlank()) {
                rl.setDescriptionText(in.description().trim());
            }
            lineRepository.save(rl);
            BigDecimal qty = usable.add(wastage);
            BigDecimal unit = lineTotal.divide(qty, UNIT_SCALE, RoundingMode.HALF_UP);
            sil.setQty(qty);
            sil.setUnitCost(unit);
            sil.setLineTotal(lineTotal);
            if (in.description() != null && !in.description().isBlank()) {
                sil.setDescription(in.description().trim());
            }
            supplierInvoiceLineRepository.save(sil);
            sumTotals = sumTotals.add(lineTotal);
        }
        inv.setSubtotal(sumTotals.setScale(2, RoundingMode.HALF_UP));
        inv.setGrandTotal(sumTotals.setScale(2, RoundingMode.HALF_UP));
        inv.setTaxTotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        supplierInvoiceRepository.save(inv);

        BigDecimal sumInv = BigDecimal.ZERO;
        BigDecimal sumWaste = BigDecimal.ZERO;
        BigDecimal apTotal = BigDecimal.ZERO;
        for (SupplierInvoiceLine sil : sils) {
            PatchPathBSupplyInvoiceLineRequest in = byId.get(sil.getId());
            RawPurchaseLine rl = lineRepository.findById(sil.getRawLineId()).orElseThrow();
            LocalDate exp = expiryByRawLine.get(rl.getId());
            reapplyPostedPathBLine(businessId, session, rl, sil.getItemId(), in.usableQty(), in.wastageQty(), exp);
            rl = lineRepository.findById(sil.getRawLineId()).orElseThrow();
            CostSplit sp = splitLine(rl.getAmountMoney(), in.usableQty(), in.wastageQty());
            sumInv = sumInv.add(sp.inventoryMoney());
            sumWaste = sumWaste.add(sp.wastageMoney());
            apTotal = apTotal.add(rl.getAmountMoney());
        }
        if (sumInv.add(sumWaste).setScale(2, RoundingMode.HALF_UP).compareTo(apTotal.setScale(2, RoundingMode.HALF_UP))
                != 0) {
            throw new IllegalStateException("Journal allocation rounding drift");
        }
        replacePathBJournalForSession(businessId, session, sumInv, sumWaste, apTotal);
    }

    private void replacePathBJournalForSession(
            String businessId,
            RawPurchaseSession session,
            BigDecimal sumInv,
            BigDecimal sumWaste,
            BigDecimal apTotal
    ) {
        JournalEntry entry = new JournalEntry();
        LocalDate invoiceDate = LocalDate.ofInstant(session.getReceivedAt(), ZoneOffset.UTC);
        entry.setBusinessId(businessId);
        entry.setEntryDate(invoiceDate);
        entry.setSourceType(PurchasingConstants.JOURNAL_SOURCE_PATH_B);
        entry.setSourceId(session.getId());
        entry.setMemo("Path B purchase rebalance " + session.getId());
        BigDecimal sumInv2 = sumInv.setScale(2, RoundingMode.HALF_UP);
        BigDecimal sumWaste2 = sumWaste.setScale(2, RoundingMode.HALF_UP);
        BigDecimal ap2 = apTotal.setScale(2, RoundingMode.HALF_UP);
        if (sumInv2.signum() > 0) {
            entry.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.INVENTORY), sumInv2);
        }
        if (sumWaste2.signum() > 0) {
            entry.debit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.INVENTORY_SHRINKAGE), sumWaste2);
        }
        entry.credit(ledgerAccountResolver.resolveId(businessId, LedgerAccountCodes.ACCOUNTS_PAYABLE), ap2);
        ledgerPostingPort.replace(businessId, PurchasingConstants.JOURNAL_SOURCE_PATH_B, session.getId(), entry);
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}