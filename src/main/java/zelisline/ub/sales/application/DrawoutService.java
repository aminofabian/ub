package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Pageable;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.ApproveDrawoutRequest;
import zelisline.ub.sales.api.dto.CreateDrawoutRequest;
import zelisline.ub.sales.api.dto.DrawoutResponse;
import zelisline.ub.sales.api.dto.RecurringDrawoutItemResponse;
import zelisline.ub.sales.api.dto.RejectDrawoutRequest;
import zelisline.ub.sales.api.dto.VoidDrawoutRequest;
import zelisline.ub.sales.domain.CashDrawout;
import zelisline.ub.sales.domain.RecurringDrawoutItem;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.domain.ShiftAuditLog;
import zelisline.ub.sales.domain.ShiftExpense;
import zelisline.ub.sales.repository.CashDrawoutRepository;
import zelisline.ub.sales.repository.RecurringDrawoutItemRepository;
import zelisline.ub.sales.repository.ShiftAuditLogRepository;
import zelisline.ub.sales.repository.ShiftExpenseRepository;
import zelisline.ub.sales.repository.ShiftRepository;

@Service
@RequiredArgsConstructor
public class DrawoutService {

    private static final BigDecimal DEFAULT_TIER_1_MAX = new BigDecimal("500.00");
    private static final BigDecimal DEFAULT_TIER_2_MAX = new BigDecimal("2000.00");
    private static final BigDecimal DEFAULT_TIER_3_MAX = new BigDecimal("10000.00");
    private static final int PENDING_EXPIRY_MINUTES = 30;

    private final CashDrawoutRepository cashDrawoutRepository;
    private final RecurringDrawoutItemRepository recurringDrawoutItemRepository;
    private final ShiftRepository shiftRepository;
    private final ShiftExpenseRepository shiftExpenseRepository;
    private final ShiftAuditLogRepository shiftAuditLogRepository;
    private final UserRepository userRepository;

    // ========================================================================
    // INITIATE DRAWOUT
    // ========================================================================

    @Transactional
    public DrawoutResponse initiateDrawout(String businessId, String shiftId, CreateDrawoutRequest request, String userId) {
        Shift shift = shiftRepository.findByIdAndBusinessId(shiftId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shift not found"));

        if (!SalesConstants.SHIFT_STATUS_OPEN.equals(shift.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Drawouts can only be created on an open shift");
        }

        // Validate category
        if (!isValidCategory(request.category())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid drawout category: " + request.category());
        }

        // Validate recurring item if category is RECURRING
        if (SalesConstants.DRAWOUT_CATEGORY_RECURRING.equals(request.category())) {
            if (request.recurringItemId() == null || request.recurringItemId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "recurringItemId is required when category is RECURRING");
            }
            RecurringDrawoutItem recurringItem = recurringDrawoutItemRepository
                    .findByIdAndBusinessId(request.recurringItemId(), businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurring item not found"));
            if (!recurringItem.isActive()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recurring item is not active");
            }
            // Check max per shift
            if (recurringItem.getMaxPerShift() != null) {
                long count = cashDrawoutRepository.countByShiftIdAndRecurringItemIdAndStatus(
                        shiftId, recurringItem.getId(), SalesConstants.DRAWOUT_STATUS_APPROVED);
                if (count >= recurringItem.getMaxPerShift()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Maximum uses per shift reached for this recurring item");
                }
            }
        }

        // Determine approval tier
        int tier = determineApprovalTier(request.amount(), request.category(), request.recurringItemId());

        // Create drawout
        CashDrawout drawout = new CashDrawout();
        drawout.setShiftId(shiftId);
        drawout.setCategory(request.category());
        drawout.setRecurringItemId(request.recurringItemId());
        drawout.setAmount(request.amount().setScale(2, RoundingMode.HALF_UP));
        drawout.setDescription(request.description());
        drawout.setRecipientName(request.recipientName());
        drawout.setRecipientContact(request.recipientContact());
        drawout.setReference(request.reference());
        drawout.setApprovalTier(tier);
        drawout.setInitiatedBy(userId);

        if (tier == SalesConstants.APPROVAL_TIER_1) {
            // Self-approved — immediately approve
            drawout.setStatus(SalesConstants.DRAWOUT_STATUS_APPROVED);
            drawout.setApprovedBy(userId);
            drawout.setApprovedAt(Instant.now());

            // Deduct from expected closing cash
            shift.setExpectedClosingCash(shift.getExpectedClosingCash()
                    .subtract(request.amount())
                    .setScale(2, RoundingMode.HALF_UP));
            shiftRepository.save(shift);

            // Record expense
            recordDrawoutExpense(shiftId, request.amount(), request.description(), userId);
        } else {
            drawout.setStatus(SalesConstants.DRAWOUT_STATUS_PENDING_APPROVAL);
            drawout.setExpiresAt(Instant.now().plusSeconds(PENDING_EXPIRY_MINUTES * 60));
        }

        cashDrawoutRepository.save(drawout);

        // Record audit log
        String auditMeta = String.format(
                "{\"category\":\"%s\",\"amount\":\"%s\",\"recipient\":\"%s\",\"tier\":%d,\"status\":\"%s\"}",
                drawout.getCategory(), drawout.getAmount(), drawout.getRecipientName(),
                drawout.getApprovalTier(), drawout.getStatus());
        recordAudit(shiftId, SalesConstants.AUDIT_DRAWOUT_INITIATED, userId, auditMeta, null);

        return toDrawoutResponse(drawout, businessId);
    }

    // ========================================================================
    // APPROVE DRAWOUT
    // ========================================================================

    @Transactional
    public DrawoutResponse approveDrawout(String businessId, String drawoutId, ApproveDrawoutRequest request, String userId) {
        CashDrawout drawout = cashDrawoutRepository.findById(drawoutId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Drawout not found"));

        if (!SalesConstants.DRAWOUT_STATUS_PENDING_APPROVAL.equals(drawout.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Drawout is not pending approval");
        }

        // Check expiry
        if (drawout.getExpiresAt() != null && Instant.now().isAfter(drawout.getExpiresAt())) {
            drawout.setStatus(SalesConstants.DRAWOUT_STATUS_EXPIRED);
            cashDrawoutRepository.save(drawout);
            recordAudit(drawout.getShiftId(), SalesConstants.AUDIT_DRAWOUT_EXPIRED, userId,
                    "{\"reason\":\"Auto-expired\",\"originalAmount\":\"" + drawout.getAmount() + "\"}", null);
            throw new ResponseStatusException(HttpStatus.GONE, "Drawout has expired");
        }

        Shift shift = shiftRepository.findByIdAndBusinessId(drawout.getShiftId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shift not found"));

        // Update drawout
        drawout.setStatus(SalesConstants.DRAWOUT_STATUS_APPROVED);
        drawout.setApprovedBy(userId);
        drawout.setApprovedAt(Instant.now());
        cashDrawoutRepository.save(drawout);

        // Deduct from expected closing cash
        shift.setExpectedClosingCash(shift.getExpectedClosingCash()
                .subtract(drawout.getAmount())
                .setScale(2, RoundingMode.HALF_UP));
        shiftRepository.save(shift);

        // Record expense
        recordDrawoutExpense(drawout.getShiftId(), drawout.getAmount(), drawout.getDescription(), userId);

        // Record audit log
        String auditMeta = String.format(
                "{\"approvalMethod\":\"%s\",\"amount\":\"%s\",\"tier\":%d}",
                request.approvalMethod(), drawout.getAmount(), drawout.getApprovalTier());
        recordAudit(drawout.getShiftId(), SalesConstants.AUDIT_DRAWOUT_APPROVED, userId, auditMeta, null);

        return toDrawoutResponse(drawout, businessId);
    }

    // ========================================================================
    // REJECT DRAWOUT
    // ========================================================================

    @Transactional
    public DrawoutResponse rejectDrawout(String businessId, String drawoutId, RejectDrawoutRequest request, String userId) {
        CashDrawout drawout = cashDrawoutRepository.findById(drawoutId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Drawout not found"));

        if (!SalesConstants.DRAWOUT_STATUS_PENDING_APPROVAL.equals(drawout.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Drawout is not pending approval");
        }

        drawout.setStatus(SalesConstants.DRAWOUT_STATUS_REJECTED);
        drawout.setRejectedBy(userId);
        drawout.setRejectionReason(request.rejectionReason());
        cashDrawoutRepository.save(drawout);

        // Record audit log
        String auditMeta = String.format(
                "{\"rejectionReason\":\"%s\",\"amount\":\"%s\"}",
                request.rejectionReason(), drawout.getAmount());
        recordAudit(drawout.getShiftId(), SalesConstants.AUDIT_DRAWOUT_REJECTED, userId, auditMeta, null);

        return toDrawoutResponse(drawout, businessId);
    }

    // ========================================================================
    // VOID DRAWOUT
    // ========================================================================

    @Transactional
    public DrawoutResponse voidDrawout(String businessId, String drawoutId, VoidDrawoutRequest request, String userId) {
        CashDrawout drawout = cashDrawoutRepository.findById(drawoutId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Drawout not found"));

        if (!SalesConstants.DRAWOUT_STATUS_APPROVED.equals(drawout.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only approved drawouts can be voided");
        }

        Shift shift = shiftRepository.findByIdAndBusinessId(drawout.getShiftId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shift not found"));

        if (SalesConstants.SHIFT_STATUS_RECONCILED.equals(shift.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot void a drawout on a reconciled shift");
        }

        drawout.setStatus(SalesConstants.DRAWOUT_STATUS_VOIDED);
        drawout.setVoidedBy(userId);
        drawout.setVoidReason(request.voidReason());
        drawout.setVoidedAt(Instant.now());
        cashDrawoutRepository.save(drawout);

        // Add amount back to expected closing cash
        shift.setExpectedClosingCash(shift.getExpectedClosingCash()
                .add(drawout.getAmount())
                .setScale(2, RoundingMode.HALF_UP));
        shiftRepository.save(shift);

        // Record audit log
        String auditMeta = String.format(
                "{\"voidReason\":\"%s\",\"amountReversed\":\"%s\"}",
                request.voidReason(), drawout.getAmount());
        recordAudit(drawout.getShiftId(), SalesConstants.AUDIT_DRAWOUT_VOIDED, userId, auditMeta, null);

        return toDrawoutResponse(drawout, businessId);
    }

    // ========================================================================
    // GET SHIFT DRAWOUTS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<DrawoutResponse> getShiftDrawouts(String businessId, String shiftId) {
        // Verify shift exists and belongs to business
        shiftRepository.findByIdAndBusinessId(shiftId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shift not found"));

        return cashDrawoutRepository.findByShiftIdOrderByCreatedAtDesc(shiftId)
                .stream()
                .map(d -> toDrawoutResponse(d, businessId))
                .toList();
    }

    // ========================================================================
    // GET PENDING DRAWOUTS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<DrawoutResponse> getPendingDrawouts(String businessId) {
        // Get open shift IDs for this business
        List<Shift> openShifts = shiftRepository.findByBusinessIdFiltered(
                businessId, null, SalesConstants.SHIFT_STATUS_OPEN, Pageable.unpaged()).getContent();

        List<CashDrawout> allPending = new ArrayList<>();
        for (Shift shift : openShifts) {
            allPending.addAll(cashDrawoutRepository.findByShiftIdAndStatusOrderByCreatedAtDesc(
                    shift.getId(), SalesConstants.DRAWOUT_STATUS_PENDING_APPROVAL));
        }

        return allPending.stream()
                .map(d -> toDrawoutResponse(d, businessId))
                .toList();
    }

    // ========================================================================
    // RECURRING ITEMS
    // ========================================================================

    @Transactional(readOnly = true)
    public List<RecurringDrawoutItemResponse> listRecurringItems(String businessId) {
        return recurringDrawoutItemRepository.findByBusinessIdAndIsActiveOrderByNameAsc(businessId, true)
                .stream()
                .map(item -> toRecurringItemResponse(item, businessId))
                .toList();
    }

    @Transactional
    public RecurringDrawoutItemResponse createRecurringItem(
            String businessId,
            String name,
            String category,
            BigDecimal defaultAmount,
            BigDecimal amountTolerance,
            String defaultDescription,
            String defaultRecipient,
            String frequency,
            Integer maxPerShift,
            boolean requiresApproval,
            String userId
    ) {
        RecurringDrawoutItem item = new RecurringDrawoutItem();
        item.setBusinessId(businessId);
        item.setName(name);
        item.setCategory(category);
        item.setDefaultAmount(defaultAmount.setScale(2, RoundingMode.HALF_UP));
        item.setAmountTolerance(amountTolerance != null
                ? amountTolerance.setScale(2, RoundingMode.HALF_UP)
                : new BigDecimal("20.00"));
        item.setDefaultDescription(defaultDescription);
        item.setDefaultRecipient(defaultRecipient);
        item.setFrequency(frequency != null ? frequency : SalesConstants.RECURRING_FREQ_DAILY);
        item.setMaxPerShift(maxPerShift);
        item.setRequiresApproval(requiresApproval);
        item.setActive(true);
        item.setCreatedBy(userId);
        recurringDrawoutItemRepository.save(item);

        return toRecurringItemResponse(item, businessId);
    }

    // ========================================================================
    // APPROVAL TIER DETERMINATION
    // ========================================================================

    /**
     * Determines the approval tier for a drawout amount.
     * Tier 1: ≤ 500 (self-approved)
     * Tier 2: 501 - 2,000 (supervisor PIN)
     * Tier 3: 2,001 - 10,000 (supervisor presence)
     * Tier 4: > 10,000 (manager only)
     *
     * If the drawout is recurring and the item requires approval, forces Tier 2 minimum.
     */
    public int determineApprovalTier(BigDecimal amount, String category, String recurringItemId) {
        // Check if recurring item forces approval
        if (recurringItemId != null && !recurringItemId.isBlank()) {
            RecurringDrawoutItem item = recurringDrawoutItemRepository.findById(recurringItemId).orElse(null);
            if (item != null && item.isRequiresApproval()) {
                return Math.max(SalesConstants.APPROVAL_TIER_2, tierByAmount(amount));
            }
        }

        return tierByAmount(amount);
    }

    private int tierByAmount(BigDecimal amount) {
        if (amount.compareTo(DEFAULT_TIER_1_MAX) <= 0) {
            return SalesConstants.APPROVAL_TIER_1;
        } else if (amount.compareTo(DEFAULT_TIER_2_MAX) <= 0) {
            return SalesConstants.APPROVAL_TIER_2;
        } else if (amount.compareTo(DEFAULT_TIER_3_MAX) <= 0) {
            return SalesConstants.APPROVAL_TIER_3;
        } else {
            return SalesConstants.APPROVAL_TIER_4;
        }
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private boolean isValidCategory(String category) {
        return SalesConstants.DRAWOUT_CATEGORY_PETTY_CASH.equals(category)
                || SalesConstants.DRAWOUT_CATEGORY_CASUAL_LABOUR.equals(category)
                || SalesConstants.DRAWOUT_CATEGORY_SUPPLIER_PAYMENT.equals(category)
                || SalesConstants.DRAWOUT_CATEGORY_RECURRING.equals(category)
                || SalesConstants.DRAWOUT_CATEGORY_OTHER.equals(category);
    }

    private void recordDrawoutExpense(String shiftId, BigDecimal amount, String description, String userId) {
        ShiftExpense expense = new ShiftExpense();
        expense.setShiftId(shiftId);
        expense.setType(SalesConstants.EXPENSE_TYPE_DRAWOUT);
        expense.setAmount(amount);
        expense.setDescription(description);
        expense.setAuthorisedBy(userId);
        shiftExpenseRepository.save(expense);
    }

    private void recordAudit(String shiftId, String eventType, String performedBy,
                              String metadata, String ipAddress) {
        ShiftAuditLog log = new ShiftAuditLog();
        log.setShiftId(shiftId);
        log.setEventType(eventType);
        log.setPerformedBy(performedBy);
        log.setMetadata(metadata);
        log.setIpAddress(ipAddress);
        shiftAuditLogRepository.save(log);
    }

    private String getUserName(String businessId, String userId) {
        if (userId == null) return null;
        return userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                .map(User::getName)
                .orElse("Unknown User");
    }

    // ========================================================================
    // DTO CONVERSION
    // ========================================================================

    private DrawoutResponse toDrawoutResponse(CashDrawout d, String businessId) {
        return new DrawoutResponse(
                d.getId(), d.getShiftId(), d.getRegisterId(),
                d.getCategory(), d.getRecurringItemId(),
                d.getAmount(), d.getDescription(),
                d.getRecipientName(), d.getRecipientContact(),
                d.getReference(), d.getStatus(), d.getApprovalTier(),
                d.getInitiatedBy(), getUserName(businessId, d.getInitiatedBy()),
                d.getApprovedBy(), getUserName(businessId, d.getApprovedBy()),
                d.getApprovedAt(),
                d.getRejectedBy(), getUserName(businessId, d.getRejectedBy()),
                d.getRejectionReason(),
                d.getVoidedBy(), getUserName(businessId, d.getVoidedBy()),
                d.getVoidReason(), d.getVoidedAt(),
                d.getExpiresAt(), d.getCreatedAt(), d.getUpdatedAt()
        );
    }

    private RecurringDrawoutItemResponse toRecurringItemResponse(RecurringDrawoutItem item, String businessId) {
        return new RecurringDrawoutItemResponse(
                item.getId(), item.getBusinessId(), item.getName(),
                item.getCategory(), item.getDefaultAmount(), item.getAmountTolerance(),
                item.getDefaultDescription(), item.getDefaultRecipient(),
                item.getFrequency(), item.getMaxPerShift(),
                item.isRequiresApproval(), item.isActive(),
                item.getCreatedBy(), getUserName(businessId, item.getCreatedBy()),
                item.getCreatedAt(), item.getUpdatedAt()
        );
    }
}
