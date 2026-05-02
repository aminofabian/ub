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
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.IdempotencyKey;
import zelisline.ub.catalog.repository.IdempotencyKeyRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerBootstrapService;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.finance.domain.LedgerAccount;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.JournalLineRepository;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.api.dto.AddPathBLineRequest;
import zelisline.ub.purchasing.api.dto.CreatePathBSessionRequest;
import zelisline.ub.purchasing.api.dto.PathBLineResponse;
import zelisline.ub.purchasing.api.dto.PathBSessionDetailResponse;
import zelisline.ub.purchasing.api.dto.PostPathBLineBreakdown;
import zelisline.ub.purchasing.api.dto.PostPathBRequest;
import zelisline.ub.purchasing.api.dto.PostPathBResponse;
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
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class PathBPurchaseService {

    private static final BigDecimal MONEY_SCALE = new BigDecimal("0.01");
    private static final int UNIT_SCALE = 4;

    private final RawPurchaseSessionRepository sessionRepository;
    private final RawPurchaseLineRepository lineRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final StockMovementRepository stockMovementRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierInvoiceLineRepository supplierInvoiceLineRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerBootstrapService ledgerBootstrapService;
    private final ItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final BranchRepository branchRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

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
        ledgerBootstrapService.ensureStandardAccounts(businessId);
        LedgerAccount invAcc = ledger(businessId, LedgerAccountCodes.INVENTORY);
        LedgerAccount apAcc = ledger(businessId, LedgerAccountCodes.ACCOUNTS_PAYABLE);
        LedgerAccount wasteAcc = ledger(businessId, LedgerAccountCodes.INVENTORY_SHRINKAGE);

        BigDecimal sumInv = BigDecimal.ZERO;
        BigDecimal sumWaste = BigDecimal.ZERO;
        BigDecimal apTotal = BigDecimal.ZERO;
        List<LinePostPlan> plans = new ArrayList<>();
        for (RawPurchaseLine line : dbLines) {
            PostPathBLineBreakdown br = req.lines().stream()
                    .filter(b -> b.lineId().equals(line.getId()))
                    .findFirst()
                    .orElseThrow();
            if (br.usableQty().signum() <= 0 && br.wastageQty().signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each line needs usable or wastage quantity");
            }
            Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(br.itemId(), businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
            CostSplit split = splitLine(line.getAmountMoney(), br.usableQty(), br.wastageQty());
            plans.add(new LinePostPlan(line, item, br.itemId(), br.usableQty(), br.wastageQty(), split));
            sumInv = sumInv.add(split.inventoryMoney());
            sumWaste = sumWaste.add(split.wastageMoney());
            apTotal = apTotal.add(line.getAmountMoney());
        }
        if (sumInv.add(sumWaste).compareTo(apTotal) != 0) {
            throw new IllegalStateException("Journal allocation rounding drift");
        }

        for (LinePostPlan plan : plans) {
            applyLinePost(businessId, session, plan);
        }

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
        for (RawPurchaseLine line : dbLines) {
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

        JournalEntry je = new JournalEntry();
        je.setBusinessId(businessId);
        je.setEntryDate(invoiceDate);
        je.setSourceType(PurchasingConstants.JOURNAL_SOURCE_PATH_B);
        je.setSourceId(session.getId());
        je.setMemo("Path B purchase " + invoiceNumber);
        journalEntryRepository.save(je);

        BigDecimal sumInv2 = sumInv.setScale(2, RoundingMode.HALF_UP);
        BigDecimal sumWaste2 = sumWaste.setScale(2, RoundingMode.HALF_UP);
        BigDecimal ap2 = apTotal.setScale(2, RoundingMode.HALF_UP);
        List<JournalLine> jl = new ArrayList<>();
        if (sumInv2.signum() > 0) {
            jl.add(journalDebit(je.getId(), invAcc.getId(), sumInv2));
        }
        if (sumWaste2.signum() > 0) {
            jl.add(journalDebit(je.getId(), wasteAcc.getId(), sumWaste2));
        }
        jl.add(journalCredit(je.getId(), apAcc.getId(), ap2));
        journalLineRepository.saveAll(jl);
        assertJournalBalanced(jl);

        session.setStatus(PurchasingConstants.SESSION_POSTED);
        sessionRepository.save(session);

        return new PostPathBResponse(inv.getId(), invoiceNumber, je.getId(), ap2, dbLines.size());
    }

    private void assertJournalBalanced(List<JournalLine> lines) {
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (JournalLine l : lines) {
            dr = dr.add(l.getDebit());
            cr = cr.add(l.getCredit());
        }
        if (dr.subtract(cr).abs().compareTo(MONEY_SCALE) > 0) {
            throw new IllegalStateException("Unbalanced journal");
        }
    }

    private static JournalLine journalDebit(String entryId, String accId, BigDecimal amount) {
        JournalLine l = new JournalLine();
        l.setJournalEntryId(entryId);
        l.setLedgerAccountId(accId);
        l.setDebit(amount.setScale(2, RoundingMode.HALF_UP));
        l.setCredit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        return l;
    }

    private static JournalLine journalCredit(String entryId, String accId, BigDecimal amount) {
        JournalLine l = new JournalLine();
        l.setJournalEntryId(entryId);
        l.setLedgerAccountId(accId);
        l.setDebit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        l.setCredit(amount.setScale(2, RoundingMode.HALF_UP));
        return l;
    }

    private void applyLinePost(String businessId, RawPurchaseSession session, LinePostPlan p) {
        RawPurchaseLine line = p.line();
        CostSplit s = p.split();
        if (p.usableQty().signum() > 0) {
            BigDecimal unitCost = s.inventoryMoney().divide(p.usableQty(), UNIT_SCALE, RoundingMode.HALF_UP);
            InventoryBatch b = new InventoryBatch();
            b.setBusinessId(businessId);
            b.setBranchId(session.getBranchId());
            b.setItemId(p.itemId());
            b.setSupplierId(session.getSupplierId());
            b.setBatchNumber("B-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            b.setSourceType(PurchasingConstants.BATCH_SOURCE_PATH_B);
            b.setSourceId(line.getId());
            b.setInitialQuantity(p.usableQty());
            b.setQuantityRemaining(p.usableQty());
            b.setUnitCost(unitCost);
            b.setReceivedAt(session.getReceivedAt());
            inventoryBatchRepository.save(b);

            StockMovement sm = new StockMovement();
            sm.setBusinessId(businessId);
            sm.setBranchId(session.getBranchId());
            sm.setItemId(p.itemId());
            sm.setBatchId(b.getId());
            sm.setMovementType(PurchasingConstants.MOVEMENT_RECEIPT);
            sm.setReferenceType(PurchasingConstants.STOCK_REF_RAW_LINE);
            sm.setReferenceId(line.getId());
            sm.setQuantityDelta(p.usableQty());
            sm.setUnitCost(unitCost);
            stockMovementRepository.save(sm);

            Item it = p.item();
            BigDecimal base = it.getCurrentStock() == null ? BigDecimal.ZERO : it.getCurrentStock();
            it.setCurrentStock(base.add(p.usableQty()));
            itemRepository.save(it);

            line.setInventoryBatchId(b.getId());
            touchSupplierProduct(session.getSupplierId(), p.itemId(), unitCost);
        }
        if (p.wastageQty().signum() > 0) {
            BigDecimal wUnit = s.wastageMoney().divide(p.wastageQty(), UNIT_SCALE, RoundingMode.HALF_UP);
            StockMovement wm = new StockMovement();
            wm.setBusinessId(businessId);
            wm.setBranchId(session.getBranchId());
            wm.setItemId(p.itemId());
            wm.setBatchId(null);
            wm.setMovementType(PurchasingConstants.MOVEMENT_WASTAGE);
            wm.setReferenceType(PurchasingConstants.STOCK_REF_RAW_LINE);
            wm.setReferenceId(line.getId());
            wm.setQuantityDelta(p.wastageQty().negate());
            wm.setUnitCost(wUnit);
            wm.setReason("Path B wastage");
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

    private LedgerAccount ledger(String businessId, String code) {
        return ledgerAccountRepository.findByBusinessIdAndCode(businessId, code)
                .orElseThrow(() -> new IllegalStateException("Missing ledger account " + code));
    }

    private static void validateBreakdown(List<RawPurchaseLine> dbLines, PostPathBRequest req) {
        if (req.lines().size() != dbLines.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Breakdown must include every line");
        }
        Set<String> ids = dbLines.stream().map(RawPurchaseLine::getId).collect(Collectors.toCollection(HashSet::new));
        Set<String> seen = new HashSet<>();
        for (PostPathBLineBreakdown b : req.lines()) {
            if (!ids.contains(b.lineId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown line id");
            }
            if (!seen.add(b.lineId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate line in breakdown");
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
            CostSplit split
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

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}