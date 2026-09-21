package zelisline.ub.finance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.catalog.domain.IdempotencyKey;
import zelisline.ub.catalog.repository.IdempotencyKeyRepository;
import zelisline.ub.finance.BusinessTimeZones;
import zelisline.ub.finance.ExpenseCategoryCodes;
import zelisline.ub.finance.FinanceConstants;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.api.dto.ExpenseListResponse;
import zelisline.ub.finance.api.dto.ExpenseResponse;
import zelisline.ub.finance.api.dto.PostExpenseRequest;
import zelisline.ub.finance.domain.Expense;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.finance.domain.LedgerAccount;
import zelisline.ub.finance.repository.ExpenseRepository;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.sales.application.CashDrawerLedgerService;
import zelisline.ub.sales.application.OpenShiftResolver;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * One-off and recurring expense posting. Drawer pay-outs that flag
 * {@code includeInCashDrawer} adjust the open shift expected cash.
 *
 * <p><b>Day list rule (ENP-3):</b> when {@code date} is omitted,
 * {@link #listExpensesForDate} uses {@code LocalDate.now(businessZone)} — same
 * calendar as pulse and the recurring scheduler — not UTC.
 */
@Service
@RequiredArgsConstructor
public class ExpenseService {

    private final LedgerPostingPort ledgerPostingPort;
    private final LedgerAccountResolver ledgerAccountResolver;
    private final ExpenseRepository expenseRepository;
    private final OpenShiftResolver openShiftResolver;
    private final BranchRepository branchRepository;
    private final BusinessRepository businessRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;
    private final CashDrawerLedgerService cashDrawerLedgerService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Value("${app.finance.expense-approval-threshold:5000}")
    private BigDecimal approvalThreshold;

    public static String recordExpenseRoute() {
        return "POST /api/v1/finance/expenses";
    }

    @Transactional
    public ExpenseResponse recordExpense(
            String businessId,
            PostExpenseRequest req,
            String userId,
            String idemKeyRaw
    ) {
        return recordExpense(businessId, req, userId, idemKeyRaw, null, true);
    }

    @Transactional
    public ExpenseResponse recordExpense(
            String businessId,
            PostExpenseRequest req,
            String userId,
            String idemKeyRaw,
            String tillDeviceKey
    ) {
        return recordExpense(businessId, req, userId, idemKeyRaw, tillDeviceKey, true);
    }

    @Transactional
    public ExpenseResponse recordExpense(
            String businessId,
            PostExpenseRequest req,
            String userId,
            String idemKeyRaw,
            String tillDeviceKey,
            boolean canManage
    ) {
        if (idemKeyRaw != null && !idemKeyRaw.isBlank()) {
            return recordExpenseIdempotent(businessId, req, userId, idemKeyRaw.trim(), tillDeviceKey, canManage);
        }
        Expense e = executeRecordExpense(businessId, req, userId, tillDeviceKey, canManage);
        return toDto(e);
    }

    @Transactional(readOnly = true)
    public ExpenseResponse getExpense(String businessId, String expenseId) {
        Expense e = expenseRepository.findByIdAndBusinessId(expenseId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found"));
        return toDto(e);
    }

    @Transactional(readOnly = true)
    public List<ExpenseResponse> listExpensesForDate(String businessId, LocalDate date) {
        ZoneId zone = BusinessTimeZones.of(businessRepository.findById(businessId).orElse(null));
        LocalDate day = date != null ? date : LocalDate.now(zone);
        return expenseRepository.findByBusinessIdAndExpenseDateOrderByCreatedAtDesc(businessId, day)
                .stream()
                .map(ExpenseService::toDto)
                .toList();
    }

    /**
     * Period list for the expenses hub. Day-scoped {@link #listExpensesForDate} remains
     * for the day ledger. {@code q} is optional name contains (case-insensitive).
     */
    @Transactional(readOnly = true)
    public ExpenseListResponse listExpensesForPeriod(
            String businessId,
            LocalDate from,
            LocalDate to,
            String branchId,
            String categoryType,
            String categoryCode,
            String source,
            String approvalStatus,
            String q,
            int page,
            int size
    ) {
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to are required");
        }
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be on or after from");
        }
        String resolvedBranch = blankToNull(branchId);
        if (resolvedBranch != null) {
            branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(resolvedBranch, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
        }
        String resolvedCategory = blankToNull(categoryType);
        if (resolvedCategory != null) {
            resolvedCategory = resolvedCategory.toLowerCase();
            if (!FinanceConstants.EXPENSE_CATEGORY_FIXED.equals(resolvedCategory)
                    && !FinanceConstants.EXPENSE_CATEGORY_VARIABLE.equals(resolvedCategory)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "categoryType must be fixed or variable");
            }
        }
        String resolvedCategoryCode;
        try {
            resolvedCategoryCode = ExpenseCategoryCodes.normalize(categoryCode);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        String resolvedSource = blankToNull(source) == null ? null : normalizeSource(source);
        String resolvedApproval = blankToNull(approvalStatus) == null ? null : normalizeApprovalStatus(approvalStatus);
        String resolvedQ = blankToNull(q);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);
        Page<Expense> result = expenseRepository.findForPeriod(
                businessId,
                from,
                to,
                resolvedBranch,
                resolvedCategory,
                resolvedCategoryCode,
                resolvedSource,
                resolvedApproval,
                resolvedQ,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "expenseDate", "createdAt"))
        );
        List<ExpenseResponse> items = result.getContent().stream().map(ExpenseService::toDto).toList();
        return new ExpenseListResponse(
                items,
                (int) Math.min(result.getTotalElements(), Integer.MAX_VALUE),
                safePage,
                safeSize,
                result.hasNext()
        );
    }

    @Transactional
    public Expense createRecurringExpense(String businessId, PostExpenseRequest req, String systemUserId) {
        return executeRecordExpense(businessId, req, systemUserId, null, true);
    }

    @Transactional
    public ExpenseResponse approveExpense(String businessId, String expenseId, String approverUserId) {
        Expense e = expenseRepository.findByIdAndBusinessId(expenseId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found"));
        if (!FinanceConstants.EXPENSE_APPROVAL_PENDING.equals(e.getApprovalStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Expense is not pending approval");
        }
        if (e.getApprovalExpiresAt() != null && Instant.now().isAfter(e.getApprovalExpiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Expense approval has expired");
        }
        if (e.getJournalEntryId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Expense already has a journal entry");
        }

        LedgerAccount expenseAcc = resolveExpenseAccount(
                businessId, e.getExpenseLedgerAccountId(), e.getCategoryCode());
        LedgerAccount creditAcc = resolvePaymentLedger(businessId, e.getPaymentMethod());

        JournalEntry entry = new JournalEntry();
        entry.setBusinessId(businessId);
        entry.setBranchId(e.getBranchId());
        entry.setEntryDate(e.getExpenseDate());
        entry.setSourceType(FinanceConstants.JOURNAL_SOURCE_EXPENSE);
        entry.setSourceId(e.getId());
        entry.setMemo("Expense " + e.getId());
        entry.debit(expenseAcc.getId(), e.getAmount());
        entry.credit(creditAcc.getId(), e.getAmount());
        String jeId = ledgerPostingPort.post(entry);

        applyDrawerIfNeeded(e, approverUserId, null);

        Instant now = Instant.now();
        e.setJournalEntryId(jeId);
        e.setApprovalStatus(FinanceConstants.EXPENSE_APPROVAL_POSTED);
        e.setApprovedBy(approverUserId);
        e.setApprovedAt(now);
        e.setApprovalExpiresAt(null);
        if (e.getPaidAt() == null) {
            e.setPaidAt(now);
        }
        expenseRepository.save(e);
        publishExpenseApproved(e, approverUserId);
        return toDto(e);
    }

    @Transactional
    public ExpenseResponse rejectExpense(String businessId, String expenseId, String approverUserId) {
        Expense e = expenseRepository.findByIdAndBusinessId(expenseId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense not found"));
        if (!FinanceConstants.EXPENSE_APPROVAL_PENDING.equals(e.getApprovalStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Expense is not pending approval");
        }
        Instant now = Instant.now();
        e.setApprovalStatus(FinanceConstants.EXPENSE_APPROVAL_REJECTED);
        e.setApprovedBy(approverUserId);
        e.setApprovedAt(now);
        e.setApprovalExpiresAt(null);
        expenseRepository.save(e);
        publishExpenseRejected(e, approverUserId);
        return toDto(e);
    }

    private ExpenseResponse recordExpenseIdempotent(
            String businessId,
            PostExpenseRequest req,
            String userId,
            String idemKey,
            String tillDeviceKey,
            boolean canManage
    ) {
        String route = recordExpenseRoute();
        String keyHash = TokenHasher.sha256Hex(idemKey);
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
                    return objectMapper.readValue(row.getResponseJson(), ExpenseResponse.class);
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }
            Expense created = executeRecordExpense(businessId, req, userId, tillDeviceKey, canManage);
            ExpenseResponse response = toDto(created);
            persistIdempotency(businessId, keyHash, bodyHash, route, response);
            return response;
        }
    }

    private void persistIdempotency(
            String businessId,
            String keyHash,
            String bodyHash,
            String route,
            ExpenseResponse response
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
        row.setHttpStatus(HttpStatus.CREATED.value());
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

    private Expense executeRecordExpense(
            String businessId,
            PostExpenseRequest req,
            String userId,
            String tillDeviceKey,
            boolean canManage
    ) {
        LocalDate expenseDate = req.expenseDate();
        if (expenseDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expenseDate is required");
        }
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        String category = normalized(req.categoryType());
        if (!FinanceConstants.EXPENSE_CATEGORY_FIXED.equals(category)
                && !FinanceConstants.EXPENSE_CATEGORY_VARIABLE.equals(category)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "categoryType must be fixed or variable");
        }
        String categoryCode;
        try {
            categoryCode = ExpenseCategoryCodes.normalize(req.categoryCode());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        BigDecimal amount = req.amount() == null ? BigDecimal.ZERO : req.amount();
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be > 0");
        }
        String payMethod = normalized(req.paymentMethod());
        if (!FinanceConstants.EXPENSE_PAY_METHOD_CASH.equals(payMethod)
                && !FinanceConstants.EXPENSE_PAY_METHOD_MPESA_MANUAL.equals(payMethod)
                && !FinanceConstants.EXPENSE_PAY_METHOD_BANK.equals(payMethod)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentMethod must be cash, mpesa_manual, or bank");
        }
        boolean includeInDrawer = req.includeInCashDrawer() != null && req.includeInCashDrawer();
        String branchId = blankToNull(req.branchId());
        if (branchId != null) {
            branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
        }

        String source = resolveSource(req.source(), includeInDrawer, payMethod);
        Instant paidAt;
        if (req.paidAt() != null) {
            paidAt = req.paidAt();
        } else if (FinanceConstants.EXPENSE_PAY_METHOD_MPESA_MANUAL.equals(payMethod)) {
            // Posted to books; money moves later via Send Money or external M-Pesa.
            paidAt = null;
        } else {
            paidAt = Instant.now();
        }
        String vendorMpesa = blankToNull(req.vendorMpesaNumber());
        if (vendorMpesa != null) {
            String normalized = StkPhoneNormalizer.normalize(vendorMpesa);
            if (normalized == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "vendorMpesaNumber must be a valid M-Pesa phone (e.g. 07… or 2547…)");
            }
            vendorMpesa = normalized;
        }

        LedgerAccount expenseAcc = resolveExpenseAccount(businessId, req.expenseLedgerAccountId(), categoryCode);

        boolean needsApproval = !canManage
                && amount.compareTo(approvalThreshold) > 0
                && FinanceConstants.EXPENSE_SOURCE_MANUAL.equals(source);

        String expenseId = UUID.randomUUID().toString();
        String jeId = null;

        if (!needsApproval) {
            LedgerAccount creditAcc = resolvePaymentLedger(businessId, payMethod);
            JournalEntry entry = new JournalEntry();
            entry.setBusinessId(businessId);
            entry.setBranchId(branchId);
            entry.setEntryDate(expenseDate);
            entry.setSourceType(FinanceConstants.JOURNAL_SOURCE_EXPENSE);
            entry.setSourceId(expenseId);
            entry.setMemo("Expense " + expenseId);
            entry.debit(expenseAcc.getId(), amount);
            entry.credit(creditAcc.getId(), amount);
            jeId = ledgerPostingPort.post(entry);

            Expense draft = new Expense();
            draft.setId(expenseId);
            draft.setBusinessId(businessId);
            draft.setBranchId(branchId);
            draft.setAmount(amount);
            draft.setPaymentMethod(payMethod);
            draft.setIncludeInCashDrawer(includeInDrawer);
            applyDrawerIfNeeded(draft, userId, tillDeviceKey);
        }

        Expense e = new Expense();
        e.setId(expenseId);
        e.setBusinessId(businessId);
        e.setBranchId(branchId);
        e.setExpenseDate(expenseDate);
        e.setName(name);
        e.setCategoryType(category);
        e.setSource(source);
        e.setCategoryCode(categoryCode);
        e.setAmount(amount);
        e.setPaymentMethod(payMethod);
        e.setVendorMpesaNumber(vendorMpesa);
        e.setPaidAt(needsApproval ? req.paidAt() : paidAt);
        e.setIncludeInCashDrawer(includeInDrawer);
        e.setReceiptS3Key(blankToNull(req.receiptS3Key()));
        e.setExpenseLedgerAccountId(expenseAcc.getId());
        e.setJournalEntryId(jeId);
        e.setCreatedBy(userId);
        if (needsApproval) {
            e.setApprovalStatus(FinanceConstants.EXPENSE_APPROVAL_PENDING);
            e.setApprovalExpiresAt(Instant.now().plus(Duration.ofHours(24)));
        } else {
            e.setApprovalStatus(FinanceConstants.EXPENSE_APPROVAL_POSTED);
        }
        expenseRepository.save(e);
        publishExpenseCreated(e, userId);
        return e;
    }

    private void applyDrawerIfNeeded(Expense e, String userId, String tillDeviceKey) {
        if (!e.isIncludeInCashDrawer()
                || !FinanceConstants.EXPENSE_PAY_METHOD_CASH.equals(e.getPaymentMethod())
                || e.getBranchId() == null) {
            return;
        }
        Optional<Shift> open = openShiftResolver.findOpenForUpdate(e.getBusinessId(), e.getBranchId(), tillDeviceKey);
        if (open.isEmpty()) {
            return;
        }
        Shift s = open.get();
        BigDecimal amount = e.getAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal expected = s.getExpectedClosingCash() == null
                ? BigDecimal.ZERO
                : s.getExpectedClosingCash().setScale(2, RoundingMode.HALF_UP);
        s.setExpectedClosingCash(expected.subtract(amount).setScale(2, RoundingMode.HALF_UP));

        cashDrawerLedgerService.recordAmount(
                s.getId(), CashDrawerLedgerService.EVENT_PAID_OUT,
                CashDrawerLedgerService.REF_EXPENSE, e.getId(),
                amount.negate(), CashDrawerLedgerService.CONFIDENCE_INFERRED, userId, null);
    }

    private void publishExpenseCreated(Expense e, String userId) {
        auditEventPublisher.publish(auditEventBuilder
                .builder(AuditEventCategory.FINANCE, AuditEventTypes.EXPENSE_CREATED, AuditEventSeverity.INFO)
                .businessId(e.getBusinessId())
                .branchId(e.getBranchId())
                .actor(userId, AuditEventActorType.USER)
                .target("expense", e.getId())
                .targetLabel(e.getName())
                .source("web_admin")
                .metadata(java.util.Map.of(
                        "amount", e.getAmount(),
                        "expenseDate", e.getExpenseDate().toString(),
                        "paymentMethod", e.getPaymentMethod(),
                        "categoryType", e.getCategoryType(),
                        "source", e.getSource() == null ? "" : e.getSource(),
                        "approvalStatus", e.getApprovalStatus() == null ? "" : e.getApprovalStatus(),
                        "journalEntryId", e.getJournalEntryId() == null ? "" : e.getJournalEntryId()
                ))
                .build());
    }

    private void publishExpenseApproved(Expense e, String userId) {
        auditEventPublisher.publish(auditEventBuilder
                .builder(AuditEventCategory.FINANCE, AuditEventTypes.EXPENSE_APPROVED, AuditEventSeverity.INFO)
                .businessId(e.getBusinessId())
                .branchId(e.getBranchId())
                .actor(userId, AuditEventActorType.USER)
                .target("expense", e.getId())
                .targetLabel(e.getName())
                .source("web_admin")
                .metadata(java.util.Map.of(
                        "amount", e.getAmount(),
                        "journalEntryId", e.getJournalEntryId() == null ? "" : e.getJournalEntryId()
                ))
                .build());
    }

    private void publishExpenseRejected(Expense e, String userId) {
        auditEventPublisher.publish(auditEventBuilder
                .builder(AuditEventCategory.FINANCE, AuditEventTypes.EXPENSE_REJECTED, AuditEventSeverity.INFO)
                .businessId(e.getBusinessId())
                .branchId(e.getBranchId())
                .actor(userId, AuditEventActorType.USER)
                .target("expense", e.getId())
                .targetLabel(e.getName())
                .source("web_admin")
                .metadata(java.util.Map.of(
                        "amount", e.getAmount()
                ))
                .build());
    }

    private LedgerAccount resolveExpenseAccount(
            String businessId,
            String expenseLedgerAccountId,
            String categoryCode
    ) {
        if (expenseLedgerAccountId == null || expenseLedgerAccountId.isBlank()) {
            return ledgerAccountResolver.resolve(businessId, ExpenseCategoryCodes.ledgerCodeFor(categoryCode));
        }
        LedgerAccount a = ledgerAccountResolver.findById(expenseLedgerAccountId.trim());
        if (!businessId.equals(a.getBusinessId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expense ledger account not in business");
        }
        if (!"expense".equalsIgnoreCase(a.getAccountType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expense ledger account must be of type expense");
        }
        return a;
    }

    private LedgerAccount resolvePaymentLedger(String businessId, String paymentMethod) {
        if (FinanceConstants.EXPENSE_PAY_METHOD_CASH.equals(paymentMethod)) {
            return ledgerAccountResolver.resolve(businessId, LedgerAccountCodes.OPERATING_CASH);
        }
        if (FinanceConstants.EXPENSE_PAY_METHOD_MPESA_MANUAL.equals(paymentMethod)) {
            return ledgerAccountResolver.resolve(businessId, LedgerAccountCodes.MPESA_CLEARING);
        }
        return ledgerAccountResolver.resolve(businessId, LedgerAccountCodes.BANK_ACCOUNT);
    }

    private static String resolveSource(String raw, boolean includeInDrawer, String payMethod) {
        String source = blankToNull(raw) == null
                ? FinanceConstants.EXPENSE_SOURCE_MANUAL
                : normalizeSource(raw);
        if (includeInDrawer
                && FinanceConstants.EXPENSE_PAY_METHOD_CASH.equals(payMethod)
                && (blankToNull(raw) == null || FinanceConstants.EXPENSE_SOURCE_MANUAL.equals(source))) {
            return FinanceConstants.EXPENSE_SOURCE_DRAWER;
        }
        return source;
    }

    private static String normalizeSource(String raw) {
        String value = normalized(raw);
        if (value.isBlank()) {
            return FinanceConstants.EXPENSE_SOURCE_MANUAL;
        }
        if (!FinanceConstants.EXPENSE_SOURCE_MANUAL.equals(value)
                && !FinanceConstants.EXPENSE_SOURCE_RECURRING.equals(value)
                && !FinanceConstants.EXPENSE_SOURCE_PAYROLL.equals(value)
                && !FinanceConstants.EXPENSE_SOURCE_DRAWER.equals(value)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "source must be manual, recurring, payroll, or drawer");
        }
        return value;
    }

    private static String normalizeApprovalStatus(String raw) {
        String value = normalized(raw);
        if (!FinanceConstants.EXPENSE_APPROVAL_POSTED.equals(value)
                && !FinanceConstants.EXPENSE_APPROVAL_PENDING.equals(value)
                && !FinanceConstants.EXPENSE_APPROVAL_REJECTED.equals(value)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "approvalStatus must be posted, pending_approval, or rejected");
        }
        return value;
    }

    private static ExpenseResponse toDto(Expense e) {
        return new ExpenseResponse(
                e.getId(),
                e.getBranchId(),
                e.getExpenseDate(),
                e.getName(),
                e.getCategoryType(),
                e.getSource(),
                e.getCategoryCode(),
                e.getAmount(),
                e.getPaymentMethod(),
                e.getVendorMpesaNumber(),
                e.getPaidAt(),
                e.isIncludeInCashDrawer(),
                e.getApprovalStatus(),
                e.getApprovedBy(),
                e.getApprovedAt(),
                e.getReceiptS3Key(),
                e.getExpenseLedgerAccountId(),
                e.getJournalEntryId(),
                e.getCreatedBy(),
                e.getCreatedAt()
        );
    }

    private static String normalized(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase();
    }

    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }
}
