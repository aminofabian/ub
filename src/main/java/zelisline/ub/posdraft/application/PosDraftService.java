package zelisline.ub.posdraft.application;

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
import zelisline.ub.posdraft.PosDraftConstants;
import zelisline.ub.posdraft.api.dto.CancelPosDraftRequest;
import zelisline.ub.posdraft.api.dto.CompletePosDraftRequest;
import zelisline.ub.posdraft.api.dto.CompletePosDraftResponse;
import zelisline.ub.posdraft.api.dto.CreatePosDraftRequest;
import zelisline.ub.posdraft.api.dto.PatchPosDraftLinesRequest;
import zelisline.ub.posdraft.api.dto.PosDraftLineInput;
import zelisline.ub.posdraft.api.dto.PosDraftLineResponse;
import zelisline.ub.posdraft.api.dto.PosDraftListResponse;
import zelisline.ub.posdraft.api.dto.PosDraftResponse;
import zelisline.ub.posdraft.api.dto.PosDraftSummaryResponse;
import zelisline.ub.posdraft.api.dto.PutPosDraftLineRequest;
import zelisline.ub.posdraft.domain.PosDraft;
import zelisline.ub.posdraft.domain.PosDraftAuditLog;
import zelisline.ub.posdraft.domain.PosDraftLine;
import zelisline.ub.posdraft.infrastructure.BranchPosSequenceAllocator;
import zelisline.ub.posdraft.repository.PosDraftAuditLogRepository;
import zelisline.ub.posdraft.repository.PosDraftLineRepository;
import zelisline.ub.posdraft.repository.PosDraftRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.PostSaleLineRequest;
import zelisline.ub.sales.api.dto.PostSaleRequest;
import zelisline.ub.sales.api.dto.SaleResponse;
import zelisline.ub.sales.application.SaleCreationOutcome;
import zelisline.ub.sales.application.SaleActorNameService;
import zelisline.ub.sales.application.SaleService;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class PosDraftService {

    private final PosDraftRepository draftRepository;
    private final PosDraftLineRepository lineRepository;
    private final PosDraftAuditLogRepository auditLogRepository;
    private final BranchPosSequenceAllocator sequenceAllocator;
    private final ItemRepository itemRepository;
    private final BranchRepository branchRepository;
    private final BusinessRepository businessRepository;
    private final SaleActorNameService saleActorNameService;
    private final FeatureFlagService featureFlagService;
    private final SaleService saleService;
    private final ShiftRepository shiftRepository;

    @Transactional
    public PosDraftResponse createDraft(String businessId, CreatePosDraftRequest request, String userId) {
        requireFeatureEnabled(businessId);

        String clientDraftId = normalizeClientDraftId(request.clientDraftId());
        var existing = draftRepository.findByBusinessIdAndClientDraftId(businessId, clientDraftId);
        if (existing.isPresent()) {
            PosDraft draft = existing.get();
            if (!draft.getBranchId().equals(request.branchId().trim())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Draft belongs to a different branch");
            }
            return toResponse(draft, loadActiveLines(draft.getId()));
        }

        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(request.branchId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));

        String currency = resolveCurrency(businessId);
        long ticketNumber = sequenceAllocator.allocateTicketNumber(request.branchId().trim());

        PosDraft draft = new PosDraft();
        draft.setBusinessId(businessId);
        draft.setBranchId(request.branchId().trim());
        draft.setTicketNumber(ticketNumber);
        draft.setStatus(PosDraftConstants.STATUS_PENDING);
        draft.setCreatedBy(userId);
        draft.setClientDraftId(clientDraftId);
        draft.setCurrency(currency);
        draft = draftRepository.save(draft);

        List<PosDraftLine> lines = applyLineInputs(draft, request.lines(), businessId);
        lineRepository.saveAll(lines);
        recomputeTotals(draft, lines);
        draft = draftRepository.save(draft);

        writeAudit(draft.getId(), userId, PosDraftConstants.AUDIT_CREATE_DRAFT, null, null, null);
        for (PosDraftLine line : lines) {
            if (!line.isDeleted()) {
                writeAudit(draft.getId(), userId, PosDraftConstants.AUDIT_ADD_LINE, line.getId(), null, lineSummary(line));
            }
        }

        return toResponse(draft, activeLines(lines));
    }

    @Transactional(readOnly = true)
    public PosDraftResponse getDraft(String businessId, String draftId, boolean includeDeleted) {
        requireFeatureEnabled(businessId);
        PosDraft draft = loadDraftOrThrow(businessId, draftId);
        List<PosDraftLine> lines = includeDeleted
                ? lineRepository.findByDraftIdOrderByLineIndexAsc(draftId)
                : loadActiveLines(draftId);
        return toResponse(draft, lines);
    }

    @Transactional(readOnly = true)
    public PosDraftListResponse listDrafts(
            String businessId,
            String branchId,
            String status,
            String createdBy,
            Integer hoursBack
    ) {
        requireFeatureEnabled(businessId);
        String resolvedStatus = status == null || status.isBlank()
                ? PosDraftConstants.STATUS_PENDING
                : status.trim();
        int hours = hoursBack == null || hoursBack <= 0
                ? PosDraftConstants.DEFAULT_LIST_HOURS
                : hoursBack;
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);

        List<PosDraft> drafts;
        if (createdBy != null && !createdBy.isBlank()) {
            drafts = draftRepository.findRecentByBranchStatusAndCreator(
                    businessId, branchId, resolvedStatus, createdBy.trim(), since);
        } else {
            drafts = draftRepository.findRecentByBranchAndStatus(
                    businessId, branchId, resolvedStatus, since);
        }

        List<PosDraftSummaryResponse> summaries = drafts.stream()
                .map(d -> toSummary(d, lineRepository.countByDraftIdAndDeletedFalse(d.getId())))
                .toList();
        return new PosDraftListResponse(summaries);
    }

    @Transactional
    public PosDraftResponse patchLines(
            String businessId,
            String draftId,
            PatchPosDraftLinesRequest request,
            String userId
    ) {
        requireFeatureEnabled(businessId);
        PosDraft draft = loadPendingDraftForUpdate(businessId, draftId, request.expectedVersion());
        List<PosDraftLine> existing = new ArrayList<>(lineRepository.findByDraftIdOrderByLineIndexAsc(draftId));

        for (PosDraftLineInput input : request.lines()) {
            upsertLineInput(draft, existing, input, businessId, userId);
        }

        lineRepository.saveAll(existing);
        recomputeTotals(draft, existing);
        draft = draftRepository.save(draft);
        return toResponse(draft, activeLines(existing));
    }

    @Transactional
    public PosDraftResponse putLine(
            String businessId,
            String draftId,
            String lineId,
            PutPosDraftLineRequest request,
            String userId
    ) {
        requireFeatureEnabled(businessId);
        PosDraft draft = loadPendingDraftForUpdate(businessId, draftId, request.expectedVersion());
        List<PosDraftLine> existing = new ArrayList<>(lineRepository.findByDraftIdOrderByLineIndexAsc(draftId));

        PosDraftLine line = existing.stream()
                .filter(l -> l.getId().equals(lineId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found"));

        String oldSummary = lineSummary(line);
        applyQuantityAndPrice(line, request.quantity(), request.unitPrice(), request.discountAmount());
        if (shouldDeleteLine(line)) {
            line.setDeleted(true);
            writeAudit(draft.getId(), userId, PosDraftConstants.AUDIT_REMOVE_LINE, line.getId(), oldSummary, null);
        } else {
            writeAudit(draft.getId(), userId, PosDraftConstants.AUDIT_UPDATE_LINE, line.getId(), oldSummary, lineSummary(line));
        }

        lineRepository.saveAll(existing);
        recomputeTotals(draft, existing);
        draft = draftRepository.save(draft);
        return toResponse(draft, activeLines(existing));
    }

    @Transactional
    public PosDraftResponse deleteLine(
            String businessId,
            String draftId,
            String lineId,
            Long expectedVersion,
            String userId
    ) {
        requireFeatureEnabled(businessId);
        PosDraft draft = loadPendingDraftForUpdate(businessId, draftId, expectedVersion);
        List<PosDraftLine> existing = new ArrayList<>(lineRepository.findByDraftIdOrderByLineIndexAsc(draftId));

        PosDraftLine line = existing.stream()
                .filter(l -> l.getId().equals(lineId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found"));

        String oldSummary = lineSummary(line);
        line.setDeleted(true);
        writeAudit(draft.getId(), userId, PosDraftConstants.AUDIT_REMOVE_LINE, line.getId(), oldSummary, null);

        lineRepository.saveAll(existing);
        recomputeTotals(draft, existing);
        draft = draftRepository.save(draft);
        return toResponse(draft, activeLines(existing));
    }

    @Transactional
    public CompletePosDraftResponse completeDraft(
            String businessId,
            String draftId,
            CompletePosDraftRequest request,
            String idempotencyKey,
            String userId,
            String roleId
    ) {
        requireFeatureEnabled(businessId);
        PosDraft draft = loadDraftOrThrow(businessId, draftId);

        if (PosDraftConstants.STATUS_COMPLETED.equals(draft.getStatus())) {
            if (draft.getSaleId() == null || draft.getSaleId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Draft completed without sale link");
            }
            SaleResponse sale = saleService.requireSale(businessId, draft.getSaleId());
            return new CompletePosDraftResponse(
                    draft.getId(),
                    draft.getTicketNumber(),
                    draft.getStatus(),
                    draft.getSaleId(),
                    sale,
                    false
            );
        }

        if (PosDraftConstants.STATUS_CANCELLED.equals(draft.getStatus())) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "Draft is cancelled");
        }

        assertVersion(draft, request.expectedVersion());

        List<PosDraftLine> activeLines = loadActiveLines(draftId);
        if (activeLines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Draft has no lines");
        }
        if (draft.getGrandTotal().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Draft total must be positive");
        }

        Shift shift = shiftRepository
                .findByBusinessIdAndBranchIdAndStatus(
                        businessId, draft.getBranchId(), SalesConstants.SHIFT_STATUS_OPEN)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No open shift"));

        List<PostSaleLineRequest> saleLines = new ArrayList<>();
        for (PosDraftLine line : activeLines) {
            saleLines.add(new PostSaleLineRequest(
                    line.getItemId(),
                    line.getQuantity(),
                    line.getUnitPrice()
            ));
        }

        String customerId = blankToNull(request.customerId());
        PostSaleRequest saleRequest = new PostSaleRequest(
                draft.getBranchId(),
                saleLines,
                request.payments(),
                request.clientSoldAt(),
                customerId
        );

        SaleCreationOutcome outcome = saleService.createSale(
                businessId,
                idempotencyKey,
                saleRequest,
                userId,
                roleId
        );

        draft.setStatus(PosDraftConstants.STATUS_COMPLETED);
        draft.setSaleId(outcome.response().id());
        draft.setShiftId(shift.getId());
        draft.setCompletedAt(Instant.now());
        if (customerId != null) {
            draft.setCustomerId(customerId);
        }
        draft = draftRepository.save(draft);

        writeAudit(draft.getId(), userId, PosDraftConstants.AUDIT_COMPLETE, null, null,
                "{\"saleId\":\"" + outcome.response().id() + "\"}");

        return new CompletePosDraftResponse(
                draft.getId(),
                draft.getTicketNumber(),
                draft.getStatus(),
                outcome.response().id(),
                outcome.response(),
                outcome.createdNew()
        );
    }

    @Transactional
    public PosDraftResponse cancelDraft(
            String businessId,
            String draftId,
            CancelPosDraftRequest request,
            String userId
    ) {
        requireFeatureEnabled(businessId);
        PosDraft draft = loadDraftOrThrow(businessId, draftId);
        if (!draft.isPending()) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "Draft is not pending");
        }
        draft.setStatus(PosDraftConstants.STATUS_CANCELLED);
        draft.setCancelledBy(userId);
        draft.setCancelledAt(Instant.now());
        if (request != null && request.reason() != null && !request.reason().isBlank()) {
            draft.setCancelledReason(request.reason().trim());
        }
        draft = draftRepository.save(draft);
        writeAudit(draft.getId(), userId, PosDraftConstants.AUDIT_CANCEL, null, null, null);
        return toResponse(draft, loadActiveLines(draftId));
    }

    private void upsertLineInput(
            PosDraft draft,
            List<PosDraftLine> existing,
            PosDraftLineInput input,
            String businessId,
            String userId
    ) {
        String lineId = blankToNull(input.lineId());
        PosDraftLine line = null;
        if (lineId != null) {
            line = existing.stream()
                    .filter(l -> l.getId().equals(lineId))
                    .findFirst()
                    .orElse(null);
        }

        if (line == null) {
            line = new PosDraftLine();
            line.setDraftId(draft.getId());
            line.setBusinessId(businessId);
            line.setLineIndex(nextLineIndex(existing));
            existing.add(line);
            populateLineFromItem(line, requireItem(businessId, input.itemId()));
            applyQuantityAndPrice(line, input.quantity(), input.unitPrice(), input.discountAmount());
            writeAudit(draft.getId(), userId, PosDraftConstants.AUDIT_ADD_LINE, line.getId(), null, lineSummary(line));
            return;
        }

        String oldSummary = lineSummary(line);
        applyQuantityAndPrice(line, input.quantity(), input.unitPrice(), input.discountAmount());
        if (shouldDeleteLine(line)) {
            line.setDeleted(true);
            writeAudit(draft.getId(), userId, PosDraftConstants.AUDIT_REMOVE_LINE, line.getId(), oldSummary, null);
        } else {
            writeAudit(draft.getId(), userId, PosDraftConstants.AUDIT_UPDATE_LINE, line.getId(), oldSummary, lineSummary(line));
        }
    }

    private List<PosDraftLine> applyLineInputs(
            PosDraft draft,
            List<PosDraftLineInput> inputs,
            String businessId
    ) {
        List<PosDraftLine> lines = new ArrayList<>();
        int index = 0;
        for (PosDraftLineInput input : inputs) {
            PosDraftLine line = new PosDraftLine();
            line.setDraftId(draft.getId());
            line.setBusinessId(businessId);
            line.setLineIndex(index++);
            populateLineFromItem(line, requireItem(businessId, input.itemId()));
            applyQuantityAndPrice(line, input.quantity(), input.unitPrice(), input.discountAmount());
            lines.add(line);
        }
        return lines;
    }

    private PosDraft loadPendingDraftForUpdate(String businessId, String draftId, Long expectedVersion) {
        PosDraft draft = loadDraftOrThrow(businessId, draftId);
        if (!draft.isPending()) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "Draft is not pending");
        }
        assertVersion(draft, expectedVersion);
        return draft;
    }

    private void assertVersion(PosDraft draft, Long expectedVersion) {
        if (expectedVersion == null) {
            return;
        }
        if (draft.getVersion() != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Draft was modified elsewhere");
        }
    }

    private PosDraft loadDraftOrThrow(String businessId, String draftId) {
        return draftRepository.findByIdAndBusinessId(draftId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found"));
    }

    private Item requireItem(String businessId, String itemId) {
        return itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId.trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found: " + itemId));
    }

    private void populateLineFromItem(PosDraftLine line, Item item) {
        line.setItemId(item.getId());
        line.setItemName(item.getName());
        line.setItemBarcode(blankToNull(item.getBarcode()));
    }

    private void applyQuantityAndPrice(
            PosDraftLine line,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discountAmount
    ) {
        BigDecimal qty = quantity.setScale(PosDraftConstants.QTY_SCALE, RoundingMode.HALF_UP);
        if (qty.signum() <= 0) {
            line.setQuantity(qty);
            line.setLineTotal(BigDecimal.ZERO.setScale(PosDraftConstants.MONEY_SCALE, RoundingMode.HALF_UP));
            return;
        }
        BigDecimal price = unitPrice.setScale(PosDraftConstants.PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal discount = discountAmount == null
                ? BigDecimal.ZERO
                : discountAmount.max(BigDecimal.ZERO).setScale(PosDraftConstants.PRICE_SCALE, RoundingMode.HALF_UP);
        line.setQuantity(qty);
        line.setUnitPrice(price);
        line.setDiscountAmount(discount);
        line.setDeleted(false);
        BigDecimal subtotal = qty.multiply(price);
        BigDecimal lineTotal = subtotal.subtract(discount)
                .setScale(PosDraftConstants.MONEY_SCALE, RoundingMode.HALF_UP);
        line.setLineTotal(lineTotal.max(BigDecimal.ZERO));
    }

    private boolean shouldDeleteLine(PosDraftLine line) {
        return line.getQuantity().signum() <= 0;
    }

    private void recomputeTotals(PosDraft draft, List<PosDraftLine> lines) {
        BigDecimal subTotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (PosDraftLine line : lines) {
            if (line.isDeleted()) {
                continue;
            }
            BigDecimal lineSub = line.getQuantity().multiply(line.getUnitPrice())
                    .setScale(PosDraftConstants.MONEY_SCALE, RoundingMode.HALF_UP);
            subTotal = subTotal.add(lineSub);
            discountTotal = discountTotal.add(line.getDiscountAmount()
                    .setScale(PosDraftConstants.MONEY_SCALE, RoundingMode.HALF_UP));
            grandTotal = grandTotal.add(line.getLineTotal());
        }
        draft.setSubTotal(subTotal.setScale(PosDraftConstants.MONEY_SCALE, RoundingMode.HALF_UP));
        draft.setDiscountTotal(discountTotal.setScale(PosDraftConstants.MONEY_SCALE, RoundingMode.HALF_UP));
        draft.setTaxTotal(BigDecimal.ZERO.setScale(PosDraftConstants.MONEY_SCALE, RoundingMode.HALF_UP));
        draft.setGrandTotal(grandTotal.setScale(PosDraftConstants.MONEY_SCALE, RoundingMode.HALF_UP));
    }

    private int nextLineIndex(List<PosDraftLine> existing) {
        return existing.stream()
                .map(PosDraftLine::getLineIndex)
                .max(Comparator.naturalOrder())
                .orElse(-1) + 1;
    }

    private List<PosDraftLine> loadActiveLines(String draftId) {
        return lineRepository.findByDraftIdOrderByLineIndexAsc(draftId).stream()
                .filter(l -> !l.isDeleted())
                .toList();
    }

    private List<PosDraftLine> activeLines(List<PosDraftLine> lines) {
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
        if (!featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_POS_DRAFTS_ENABLED)) {
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
        PosDraftAuditLog log = new PosDraftAuditLog();
        log.setDraftId(draftId);
        log.setUserId(userId);
        log.setAction(action);
        log.setLineId(lineId);
        log.setOldValue(oldValue);
        log.setNewValue(newValue);
        auditLogRepository.save(log);
    }

    private String lineSummary(PosDraftLine line) {
        return "{\"itemId\":\"" + line.getItemId() + "\",\"qty\":"
                + line.getQuantity().stripTrailingZeros().toPlainString()
                + ",\"unitPrice\":" + line.getUnitPrice().stripTrailingZeros().toPlainString() + "}";
    }

    private PosDraftResponse toResponse(PosDraft draft, List<PosDraftLine> lines) {
        String createdByName = saleActorNameService.resolveSoldByName(draft.getBusinessId(), draft.getCreatedBy());
        List<PosDraftLineResponse> lineResponses = lines.stream()
                .sorted(Comparator.comparingInt(PosDraftLine::getLineIndex))
                .map(this::toLineResponse)
                .toList();
        return new PosDraftResponse(
                draft.getId(),
                draft.getTicketNumber(),
                draft.getStatus(),
                draft.getBranchId(),
                draft.getClientDraftId(),
                draft.getCurrency(),
                draft.getSubTotal(),
                draft.getDiscountTotal(),
                draft.getTaxTotal(),
                draft.getGrandTotal(),
                draft.getVersion(),
                draft.getCreatedBy(),
                createdByName,
                draft.getSaleId(),
                draft.getCreatedAt(),
                draft.getUpdatedAt(),
                lineResponses
        );
    }

    private PosDraftLineResponse toLineResponse(PosDraftLine line) {
        return new PosDraftLineResponse(
                line.getId(),
                line.getLineIndex(),
                line.getItemId(),
                line.getItemName(),
                line.getItemBarcode(),
                line.getQuantity(),
                line.getUnitPrice(),
                line.getDiscountAmount(),
                line.getLineTotal()
        );
    }

    private PosDraftSummaryResponse toSummary(PosDraft draft, int lineCount) {
        return new PosDraftSummaryResponse(
                draft.getId(),
                draft.getTicketNumber(),
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
