package zelisline.ub.grocery.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.grocery.GroceryDraftConstants;
import zelisline.ub.grocery.api.dto.CancelGroceryDraftRequest;
import zelisline.ub.grocery.api.dto.CreateGroceryDraftRequest;
import zelisline.ub.grocery.api.dto.CreateGroceryInvoiceLineRequest;
import zelisline.ub.grocery.api.dto.CreateGroceryInvoiceRequest;
import zelisline.ub.grocery.api.dto.GroceryDraftLineInput;
import zelisline.ub.grocery.api.dto.GroceryDraftLineResponse;
import zelisline.ub.grocery.api.dto.GroceryDraftListResponse;
import zelisline.ub.grocery.api.dto.GroceryDraftResponse;
import zelisline.ub.grocery.api.dto.GroceryDraftSummaryResponse;
import zelisline.ub.grocery.api.dto.GroceryInvoiceResponse;
import zelisline.ub.grocery.api.dto.IssueGroceryDraftRequest;
import zelisline.ub.grocery.api.dto.IssueGroceryDraftResponse;
import zelisline.ub.grocery.api.dto.PatchGroceryDraftLinesRequest;
import zelisline.ub.grocery.api.dto.PutGroceryDraftLineRequest;
import zelisline.ub.grocery.domain.GroceryDraft;
import zelisline.ub.grocery.domain.GroceryDraftAuditLog;
import zelisline.ub.grocery.domain.GroceryDraftLine;
import zelisline.ub.grocery.infrastructure.BranchGrocerySequenceAllocator;
import zelisline.ub.grocery.repository.GroceryDraftAuditLogRepository;
import zelisline.ub.grocery.repository.GroceryDraftLineRepository;
import zelisline.ub.grocery.repository.GroceryDraftRepository;
import zelisline.ub.sales.application.SaleActorNameService;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class GroceryDraftService {

    private final GroceryDraftRepository draftRepository;
    private final GroceryDraftLineRepository lineRepository;
    private final GroceryDraftAuditLogRepository auditLogRepository;
    private final BranchGrocerySequenceAllocator sequenceAllocator;
    private final ItemRepository itemRepository;
    private final BranchRepository branchRepository;
    private final BusinessRepository businessRepository;
    private final GroceryInvoiceService invoiceService;
    private final SaleActorNameService saleActorNameService;
    private final FeatureFlagService featureFlagService;

    @Transactional
    public GroceryDraftResponse createDraft(String businessId, CreateGroceryDraftRequest request, String userId) {
        requireFeatureEnabled(businessId);

        String clientDraftId = normalizeClientDraftId(request.clientDraftId());
        var existing = draftRepository.findByBusinessIdAndClientDraftId(businessId, clientDraftId);
        if (existing.isPresent()) {
            GroceryDraft draft = existing.get();
            if (!draft.getBranchId().equals(request.branchId().trim())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Draft belongs to a different branch");
            }
            return toResponse(draft, loadActiveLines(draft.getId()));
        }

        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(request.branchId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));

        var activeBuilding = draftRepository.findByBusinessIdAndBranchIdAndCreatedByAndStatus(
                businessId,
                request.branchId().trim(),
                userId,
                GroceryDraftConstants.STATUS_BUILDING
        );
        if (activeBuilding.isPresent()) {
            GroceryDraft draft = activeBuilding.get();
            List<GroceryDraftLine> existingLines = new ArrayList<>(
                    lineRepository.findByDraftIdOrderByLineIndexAsc(draft.getId())
            );
            for (GroceryDraftLineInput input : request.lines()) {
                upsertLineInput(draft, existingLines, input, businessId, userId);
            }
            lineRepository.saveAll(existingLines);
            recomputeTotals(draft, existingLines);
            draft = draftRepository.save(draft);
            return toResponse(draft, activeLines(existingLines));
        }

        String currency = resolveCurrency(businessId);
        long counterNumber = sequenceAllocator.allocateCounterNumber(request.branchId().trim());

        GroceryDraft draft = new GroceryDraft();
        draft.setBusinessId(businessId);
        draft.setBranchId(request.branchId().trim());
        draft.setCounterNumber(counterNumber);
        draft.setStatus(GroceryDraftConstants.STATUS_BUILDING);
        draft.setCreatedBy(userId);
        draft.setClientDraftId(clientDraftId);
        draft.setCurrency(currency);
        draft = draftRepository.save(draft);

        List<GroceryDraftLine> lines = applyLineInputs(draft, request.lines(), businessId);
        lineRepository.saveAll(lines);
        recomputeTotals(draft, lines);
        draft = draftRepository.save(draft);

        writeAudit(draft.getId(), userId, GroceryDraftConstants.AUDIT_CREATE_DRAFT, null, null, null);
        for (GroceryDraftLine line : lines) {
            if (!line.isDeleted()) {
                writeAudit(draft.getId(), userId, GroceryDraftConstants.AUDIT_ADD_LINE, line.getId(), null, lineSummary(line));
            }
        }

        return toResponse(draft, activeLines(lines));
    }

    @Transactional(readOnly = true)
    public GroceryDraftResponse getDraft(String businessId, String draftId, boolean includeDeleted) {
        requireFeatureEnabled(businessId);
        GroceryDraft draft = loadDraftOrThrow(businessId, draftId);
        List<GroceryDraftLine> lines = includeDeleted
                ? lineRepository.findByDraftIdOrderByLineIndexAsc(draftId)
                : loadActiveLines(draftId);
        return toResponse(draft, lines);
    }

    @Transactional(readOnly = true)
    public GroceryDraftListResponse listDrafts(
            String businessId,
            String branchId,
            String status,
            String createdBy,
            Integer hoursBack,
            Integer staleMinutes
    ) {
        requireFeatureEnabled(businessId);
        String resolvedStatus = status == null || status.isBlank()
                ? GroceryDraftConstants.STATUS_BUILDING
                : status.trim();
        int hours = hoursBack == null || hoursBack <= 0
                ? GroceryDraftConstants.DEFAULT_LIST_HOURS
                : hoursBack;
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);

        List<GroceryDraft> drafts;
        if (createdBy != null && !createdBy.isBlank()) {
            drafts = draftRepository.findRecentByBranchStatusAndCreator(
                    businessId, branchId, resolvedStatus, createdBy.trim(), since);
        } else {
            drafts = draftRepository.findRecentByBranchAndStatus(
                    businessId, branchId, resolvedStatus, since);
        }

        if (staleMinutes != null && staleMinutes > 0) {
            Instant staleBefore = Instant.now().minus(staleMinutes, ChronoUnit.MINUTES);
            drafts = drafts.stream()
                    .filter(d -> d.getUpdatedAt() != null && d.getUpdatedAt().isBefore(staleBefore))
                    .toList();
        }

        List<GroceryDraftSummaryResponse> summaries = drafts.stream()
                .map(d -> toSummary(d, lineRepository.countByDraftIdAndDeletedFalse(d.getId())))
                .toList();
        return new GroceryDraftListResponse(summaries);
    }

    @Transactional
    public GroceryDraftResponse patchLines(
            String businessId,
            String draftId,
            PatchGroceryDraftLinesRequest request,
            String userId
    ) {
        requireFeatureEnabled(businessId);
        GroceryDraft draft = loadBuildingDraftForUpdate(businessId, draftId, request.expectedVersion());
        List<GroceryDraftLine> existing = new ArrayList<>(lineRepository.findByDraftIdOrderByLineIndexAsc(draftId));

        for (GroceryDraftLineInput input : request.lines()) {
            upsertLineInput(draft, existing, input, businessId, userId);
        }

        lineRepository.saveAll(existing);
        recomputeTotals(draft, existing);
        draft = draftRepository.save(draft);
        return toResponse(draft, activeLines(existing));
    }

    @Transactional
    public GroceryDraftResponse putLine(
            String businessId,
            String draftId,
            String lineId,
            PutGroceryDraftLineRequest request,
            String userId
    ) {
        requireFeatureEnabled(businessId);
        GroceryDraft draft = loadBuildingDraftForUpdate(businessId, draftId, request.expectedVersion());
        List<GroceryDraftLine> existing = new ArrayList<>(lineRepository.findByDraftIdOrderByLineIndexAsc(draftId));

        GroceryDraftLine line = existing.stream()
                .filter(l -> l.getId().equals(lineId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found"));

        String oldSummary = lineSummary(line);
        applyQuantityAndPrice(line, request.quantity(), request.unitPrice(), request.discountAmount(), request.unitName());
        if (shouldDeleteLine(line)) {
            line.setDeleted(true);
            writeAudit(draft.getId(), userId, GroceryDraftConstants.AUDIT_REMOVE_LINE, line.getId(), oldSummary, null);
        } else {
            writeAudit(draft.getId(), userId, GroceryDraftConstants.AUDIT_UPDATE_LINE, line.getId(), oldSummary, lineSummary(line));
        }

        lineRepository.saveAll(existing);
        recomputeTotals(draft, existing);
        draft = draftRepository.save(draft);
        return toResponse(draft, activeLines(existing));
    }

    @Transactional
    public GroceryDraftResponse deleteLine(
            String businessId,
            String draftId,
            String lineId,
            Long expectedVersion,
            String userId
    ) {
        requireFeatureEnabled(businessId);
        GroceryDraft draft = loadBuildingDraftForUpdate(businessId, draftId, expectedVersion);
        List<GroceryDraftLine> existing = new ArrayList<>(lineRepository.findByDraftIdOrderByLineIndexAsc(draftId));

        GroceryDraftLine line = existing.stream()
                .filter(l -> l.getId().equals(lineId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found"));

        String oldSummary = lineSummary(line);
        line.setDeleted(true);
        writeAudit(draft.getId(), userId, GroceryDraftConstants.AUDIT_REMOVE_LINE, line.getId(), oldSummary, null);

        lineRepository.saveAll(existing);
        recomputeTotals(draft, existing);
        draft = draftRepository.save(draft);
        return toResponse(draft, activeLines(existing));
    }

    @Transactional
    public IssueGroceryDraftResponse issueDraft(
            String businessId,
            String draftId,
            IssueGroceryDraftRequest request,
            String idempotencyKey,
            String userId
    ) {
        requireFeatureEnabled(businessId);
        GroceryDraft draft = loadDraftOrThrow(businessId, draftId);

        if (idempotencyKey != null
                && idempotencyKey.equals(draft.getIssueIdempotencyKey())
                && GroceryDraftConstants.STATUS_ISSUED.equals(draft.getStatus())) {
            if (draft.getInvoiceId() == null || draft.getInvoiceId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Draft issued without invoice link");
            }
            GroceryInvoiceResponse invoice = invoiceService.getInvoice(businessId, draft.getInvoiceId());
            return new IssueGroceryDraftResponse(
                    draft.getId(),
                    draft.getCounterNumber(),
                    draft.getStatus(),
                    invoice.id(),
                    invoice,
                    false
            );
        }

        if (GroceryDraftConstants.STATUS_ISSUED.equals(draft.getStatus())) {
            if (draft.getInvoiceId() == null || draft.getInvoiceId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Draft issued without invoice link");
            }
            GroceryInvoiceResponse invoice = invoiceService.getInvoice(businessId, draft.getInvoiceId());
            return new IssueGroceryDraftResponse(
                    draft.getId(),
                    draft.getCounterNumber(),
                    draft.getStatus(),
                    invoice.id(),
                    invoice,
                    false
            );
        }

        if (GroceryDraftConstants.STATUS_CANCELLED.equals(draft.getStatus())) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "Draft is cancelled");
        }

        if (!draft.isBuilding()) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "Draft is not building");
        }

        assertVersion(draft, request.expectedVersion());

        List<GroceryDraftLine> activeLines = loadActiveLines(draftId);
        if (activeLines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Draft has no lines");
        }
        if (draft.getGrandTotal().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Draft total must be positive");
        }

        Instant maxAge = Instant.now().minus(GroceryDraftConstants.DEFAULT_MAX_AGE_HOURS, ChronoUnit.HOURS);
        if (draft.getCreatedAt() != null && draft.getCreatedAt().isBefore(maxAge)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Draft is too old to issue; please review prices");
        }

        List<CreateGroceryInvoiceLineRequest> invoiceLines = new ArrayList<>();
        for (GroceryDraftLine line : activeLines) {
            invoiceLines.add(new CreateGroceryInvoiceLineRequest(
                    line.getItemId(),
                    line.getQuantity(),
                    line.getUnitPrice(),
                    line.getUnitName()
            ));
        }

        String notes = request.notes();
        if (notes == null || notes.isBlank()) {
            notes = draft.getNotes();
        }

        CreateGroceryInvoiceRequest invoiceRequest = new CreateGroceryInvoiceRequest(
                draft.getBranchId(),
                invoiceLines,
                notes
        );

        GroceryInvoiceResponse invoice = invoiceService.createInvoice(businessId, invoiceRequest, userId);

        draft.setStatus(GroceryDraftConstants.STATUS_ISSUED);
        draft.setInvoiceId(invoice.id());
        draft.setIssueIdempotencyKey(idempotencyKey);
        draft.setIssuedAt(Instant.now());
        draft = draftRepository.save(draft);

        writeAudit(draft.getId(), userId, GroceryDraftConstants.AUDIT_ISSUE, null, null,
                "{\"invoiceId\":\"" + invoice.id() + "\",\"barcodeCode\":\"" + invoice.barcodeCode() + "\"}");

        return new IssueGroceryDraftResponse(
                draft.getId(),
                draft.getCounterNumber(),
                draft.getStatus(),
                invoice.id(),
                invoice,
                true
        );
    }

    @Transactional
    public GroceryDraftResponse cancelDraft(
            String businessId,
            String draftId,
            CancelGroceryDraftRequest request,
            String userId
    ) {
        requireFeatureEnabled(businessId);
        GroceryDraft draft = loadDraftOrThrow(businessId, draftId);
        if (!draft.isBuilding()) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "Draft is not building");
        }
        draft.setStatus(GroceryDraftConstants.STATUS_CANCELLED);
        draft.setCancelledBy(userId);
        draft.setCancelledAt(Instant.now());
        if (request != null && request.reason() != null && !request.reason().isBlank()) {
            draft.setCancelledReason(request.reason().trim());
        }
        draft = draftRepository.save(draft);
        writeAudit(draft.getId(), userId, GroceryDraftConstants.AUDIT_CANCEL, null, null, null);
        return toResponse(draft, loadActiveLines(draftId));
    }

    private void upsertLineInput(
            GroceryDraft draft,
            List<GroceryDraftLine> existing,
            GroceryDraftLineInput input,
            String businessId,
            String userId
    ) {
        String lineId = blankToNull(input.lineId());
        GroceryDraftLine line = null;
        if (lineId != null) {
            line = existing.stream()
                    .filter(l -> l.getId().equals(lineId))
                    .findFirst()
                    .orElse(null);
        }

        if (line == null) {
            line = new GroceryDraftLine();
            line.setDraftId(draft.getId());
            line.setBusinessId(businessId);
            line.setLineIndex(nextLineIndex(existing));
            existing.add(line);
            populateLineFromItem(line, requireItem(businessId, input.itemId()));
            applyQuantityAndPrice(line, input.quantity(), input.unitPrice(), input.discountAmount(), input.unitName());
            writeAudit(draft.getId(), userId, GroceryDraftConstants.AUDIT_ADD_LINE, line.getId(), null, lineSummary(line));
            return;
        }

        String oldSummary = lineSummary(line);
        applyQuantityAndPrice(line, input.quantity(), input.unitPrice(), input.discountAmount(), input.unitName());
        if (shouldDeleteLine(line)) {
            line.setDeleted(true);
            writeAudit(draft.getId(), userId, GroceryDraftConstants.AUDIT_REMOVE_LINE, line.getId(), oldSummary, null);
        } else {
            writeAudit(draft.getId(), userId, GroceryDraftConstants.AUDIT_UPDATE_LINE, line.getId(), oldSummary, lineSummary(line));
        }
    }

    private List<GroceryDraftLine> applyLineInputs(
            GroceryDraft draft,
            List<GroceryDraftLineInput> inputs,
            String businessId
    ) {
        List<GroceryDraftLine> lines = new ArrayList<>();
        int index = 0;
        for (GroceryDraftLineInput input : inputs) {
            GroceryDraftLine line = new GroceryDraftLine();
            line.setDraftId(draft.getId());
            line.setBusinessId(businessId);
            line.setLineIndex(index++);
            populateLineFromItem(line, requireItem(businessId, input.itemId()));
            applyQuantityAndPrice(line, input.quantity(), input.unitPrice(), input.discountAmount(), input.unitName());
            lines.add(line);
        }
        return lines;
    }

    private GroceryDraft loadBuildingDraftForUpdate(String businessId, String draftId, Long expectedVersion) {
        GroceryDraft draft = loadDraftOrThrow(businessId, draftId);
        if (!draft.isBuilding()) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "Draft is not building");
        }
        assertVersion(draft, expectedVersion);
        return draft;
    }

    private void assertVersion(GroceryDraft draft, Long expectedVersion) {
        if (expectedVersion == null) {
            return;
        }
        if (draft.getVersion() != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Draft was modified elsewhere");
        }
    }

    private GroceryDraft loadDraftOrThrow(String businessId, String draftId) {
        return draftRepository.findByIdAndBusinessId(draftId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found"));
    }

    private Item requireItem(String businessId, String itemId) {
        return itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId.trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found: " + itemId));
    }

    private void populateLineFromItem(GroceryDraftLine line, Item item) {
        line.setItemId(item.getId());
        line.setItemName(item.getName());
        line.setItemBarcode(blankToNull(item.getBarcode()));
    }

    private void applyQuantityAndPrice(
            GroceryDraftLine line,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discountAmount,
            String unitName
    ) {
        BigDecimal qty = quantity.setScale(GroceryDraftConstants.QTY_SCALE, RoundingMode.HALF_UP);
        if (qty.signum() <= 0) {
            line.setQuantity(qty);
            line.setLineTotal(BigDecimal.ZERO.setScale(GroceryDraftConstants.MONEY_SCALE, RoundingMode.HALF_UP));
            return;
        }
        BigDecimal price = unitPrice.setScale(GroceryDraftConstants.PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal discount = discountAmount == null
                ? BigDecimal.ZERO
                : discountAmount.max(BigDecimal.ZERO).setScale(GroceryDraftConstants.PRICE_SCALE, RoundingMode.HALF_UP);
        line.setQuantity(qty);
        line.setUnitPrice(price);
        line.setDiscountAmount(discount);
        line.setDeleted(false);
        if (unitName != null && !unitName.isBlank()) {
            line.setUnitName(unitName.trim());
        }
        BigDecimal subtotal = qty.multiply(price);
        BigDecimal lineTotal = subtotal.subtract(discount)
                .setScale(GroceryDraftConstants.MONEY_SCALE, RoundingMode.HALF_UP);
        line.setLineTotal(lineTotal.max(BigDecimal.ZERO));
    }

    private boolean shouldDeleteLine(GroceryDraftLine line) {
        return line.getQuantity().signum() <= 0;
    }

    private void recomputeTotals(GroceryDraft draft, List<GroceryDraftLine> lines) {
        BigDecimal subTotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (GroceryDraftLine line : lines) {
            if (line.isDeleted()) {
                continue;
            }
            BigDecimal lineSub = line.getQuantity().multiply(line.getUnitPrice())
                    .setScale(GroceryDraftConstants.MONEY_SCALE, RoundingMode.HALF_UP);
            subTotal = subTotal.add(lineSub);
            discountTotal = discountTotal.add(line.getDiscountAmount()
                    .setScale(GroceryDraftConstants.MONEY_SCALE, RoundingMode.HALF_UP));
            grandTotal = grandTotal.add(line.getLineTotal());
        }
        draft.setSubTotal(subTotal.setScale(GroceryDraftConstants.MONEY_SCALE, RoundingMode.HALF_UP));
        draft.setDiscountTotal(discountTotal.setScale(GroceryDraftConstants.MONEY_SCALE, RoundingMode.HALF_UP));
        draft.setTaxTotal(BigDecimal.ZERO.setScale(GroceryDraftConstants.MONEY_SCALE, RoundingMode.HALF_UP));
        draft.setGrandTotal(grandTotal.setScale(GroceryDraftConstants.MONEY_SCALE, RoundingMode.HALF_UP));
    }

    private int nextLineIndex(List<GroceryDraftLine> existing) {
        return existing.stream()
                .map(GroceryDraftLine::getLineIndex)
                .max(Comparator.naturalOrder())
                .orElse(-1) + 1;
    }

    private List<GroceryDraftLine> loadActiveLines(String draftId) {
        return lineRepository.findByDraftIdOrderByLineIndexAsc(draftId).stream()
                .filter(l -> !l.isDeleted())
                .toList();
    }

    private List<GroceryDraftLine> activeLines(List<GroceryDraftLine> lines) {
        return lines.stream().filter(l -> !l.isDeleted()).toList();
    }

    private String resolveCurrency(String businessId) {
        return businessRepository.findById(businessId)
                .map(Business::getCurrency)
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .orElse("KES");
    }

    private void requireFeatureEnabled(String businessId) {
        if (!featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_GROCERY_DRAFTS_ENABLED)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
    }

    private void writeAudit(
            String draftId,
            String userId,
            String action,
            String lineId,
            String oldValue,
            String newValue
    ) {
        GroceryDraftAuditLog log = new GroceryDraftAuditLog();
        log.setDraftId(draftId);
        log.setUserId(userId);
        log.setAction(action);
        log.setLineId(lineId);
        log.setOldValue(oldValue);
        log.setNewValue(newValue);
        auditLogRepository.save(log);
    }

    private String lineSummary(GroceryDraftLine line) {
        return "{\"itemId\":\"" + line.getItemId() + "\",\"qty\":"
                + line.getQuantity().stripTrailingZeros().toPlainString()
                + ",\"unitPrice\":" + line.getUnitPrice().stripTrailingZeros().toPlainString()
                + ",\"unitName\":\"" + line.getUnitName() + "\"}";
    }

    private GroceryDraftResponse toResponse(GroceryDraft draft, List<GroceryDraftLine> lines) {
        String createdByName = saleActorNameService.resolveSoldByName(draft.getBusinessId(), draft.getCreatedBy());
        List<GroceryDraftLineResponse> lineResponses = lines.stream()
                .sorted(Comparator.comparingInt(GroceryDraftLine::getLineIndex))
                .map(this::toLineResponse)
                .toList();
        return new GroceryDraftResponse(
                draft.getId(),
                draft.getCounterNumber(),
                draft.getStatus(),
                draft.getBranchId(),
                draft.getClientDraftId(),
                draft.getInvoiceId(),
                draft.getNotes(),
                draft.getCurrency(),
                draft.getSubTotal(),
                draft.getDiscountTotal(),
                draft.getTaxTotal(),
                draft.getGrandTotal(),
                draft.getVersion(),
                draft.getCreatedBy(),
                createdByName,
                draft.getCreatedAt(),
                draft.getUpdatedAt(),
                draft.getIssuedAt(),
                draft.getCancelledAt(),
                draft.getCancelledReason(),
                lineResponses
        );
    }

    private GroceryDraftLineResponse toLineResponse(GroceryDraftLine line) {
        return new GroceryDraftLineResponse(
                line.getId(),
                line.getLineIndex(),
                line.getItemId(),
                line.getItemName(),
                line.getItemBarcode(),
                line.getQuantity(),
                line.getUnitName(),
                line.getUnitPrice(),
                line.getDiscountAmount(),
                line.getLineTotal()
        );
    }

    private GroceryDraftSummaryResponse toSummary(GroceryDraft draft, int lineCount) {
        return new GroceryDraftSummaryResponse(
                draft.getId(),
                draft.getCounterNumber(),
                draft.getStatus(),
                draft.getBranchId(),
                lineCount,
                draft.getGrandTotal(),
                draft.getCurrency(),
                draft.getCreatedBy(),
                saleActorNameService.resolveSoldByName(draft.getBusinessId(), draft.getCreatedBy()),
                draft.getCreatedAt(),
                draft.getUpdatedAt()
        );
    }

    private static String normalizeClientDraftId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientDraftId is required");
        }
        return raw.trim();
    }

    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }
}
