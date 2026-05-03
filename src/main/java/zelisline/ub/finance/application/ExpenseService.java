package zelisline.ub.finance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.IdempotencyKey;
import zelisline.ub.catalog.repository.IdempotencyKeyRepository;
import zelisline.ub.finance.FinanceConstants;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.api.dto.ExpenseResponse;
import zelisline.ub.finance.api.dto.PostExpenseRequest;
import zelisline.ub.finance.domain.Expense;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.finance.domain.LedgerAccount;
import zelisline.ub.finance.repository.ExpenseRepository;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.JournalLineRepository;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Shift;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class ExpenseService {

    private static final BigDecimal MONEY_TOL = new BigDecimal("0.01");

    private final LedgerBootstrapService ledgerBootstrapService;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;
    private final ExpenseRepository expenseRepository;
    private final ShiftRepository shiftRepository;
    private final BranchRepository branchRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    public static String recordExpenseRoute() {
        return "POST /api/v1/finance/expenses";
    }

    @Transactional
    public ExpenseResponse recordExpense(String businessId, PostExpenseRequest req, String userId, String idemKeyRaw) {
        if (idemKeyRaw != null && !idemKeyRaw.isBlank()) {
            return recordExpenseIdempotent(businessId, req, userId, idemKeyRaw.trim());
        }
        Expense e = executeRecordExpense(businessId, req, userId);
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
        LocalDate day = date != null ? date : LocalDate.now(ZoneOffset.UTC);
        return expenseRepository.findByBusinessIdAndExpenseDateOrderByCreatedAtDesc(businessId, day)
                .stream()
                .map(ExpenseService::toDto)
                .toList();
    }

    @Transactional
    public Expense createRecurringExpense(String businessId, PostExpenseRequest req, String systemUserId) {
        return executeRecordExpense(businessId, req, systemUserId);
    }

    private ExpenseResponse recordExpenseIdempotent(
            String businessId,
            PostExpenseRequest req,
            String userId,
            String idemKey
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
            Expense created = executeRecordExpense(businessId, req, userId);
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

    private Expense executeRecordExpense(String businessId, PostExpenseRequest req, String userId) {
        ledgerBootstrapService.ensureStandardAccounts(businessId);
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

        LedgerAccount expenseAcc = resolveExpenseAccount(businessId, req.expenseLedgerAccountId());
        LedgerAccount creditAcc = resolvePaymentLedger(businessId, payMethod);

        String expenseId = UUID.randomUUID().toString();
        JournalEntry je = new JournalEntry();
        je.setBusinessId(businessId);
        je.setEntryDate(expenseDate);
        je.setSourceType(FinanceConstants.JOURNAL_SOURCE_EXPENSE);
        je.setSourceId(expenseId);
        je.setMemo("Expense " + expenseId);
        journalEntryRepository.save(je);

        List<JournalLine> lines = new ArrayList<>(2);
        lines.add(journalDebit(je.getId(), expenseAcc.getId(), amount));
        lines.add(journalCredit(je.getId(), creditAcc.getId(), amount));
        journalLineRepository.saveAll(lines);
        assertBalanced(lines);

        if (includeInDrawer && FinanceConstants.EXPENSE_PAY_METHOD_CASH.equals(payMethod) && branchId != null) {
            Optional<Shift> open = shiftRepository.findByBusinessIdAndBranchIdAndStatusForUpdate(
                    businessId, branchId, SalesConstants.SHIFT_STATUS_OPEN);
            if (open.isPresent()) {
                Shift s = open.get();
                BigDecimal expected = s.getExpectedClosingCash() == null
                        ? BigDecimal.ZERO
                        : s.getExpectedClosingCash().setScale(2, RoundingMode.HALF_UP);
                s.setExpectedClosingCash(expected.subtract(amount).setScale(2, RoundingMode.HALF_UP));
                shiftRepository.save(s);
            }
        }

        Expense e = new Expense();
        e.setId(expenseId);
        e.setBusinessId(businessId);
        e.setBranchId(branchId);
        e.setExpenseDate(expenseDate);
        e.setName(name);
        e.setCategoryType(category);
        e.setAmount(amount);
        e.setPaymentMethod(payMethod);
        e.setIncludeInCashDrawer(includeInDrawer);
        e.setReceiptS3Key(blankToNull(req.receiptS3Key()));
        e.setExpenseLedgerAccountId(expenseAcc.getId());
        e.setJournalEntryId(je.getId());
        e.setCreatedBy(userId);
        expenseRepository.save(e);
        return e;
    }

    private LedgerAccount resolveExpenseAccount(String businessId, String expenseLedgerAccountId) {
        if (expenseLedgerAccountId == null || expenseLedgerAccountId.isBlank()) {
            return ledgerByCode(businessId, LedgerAccountCodes.OPERATING_EXPENSES);
        }
        LedgerAccount a = ledgerAccountRepository.findById(expenseLedgerAccountId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expense ledger account not found"));
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
            return ledgerByCode(businessId, LedgerAccountCodes.OPERATING_CASH);
        }
        if (FinanceConstants.EXPENSE_PAY_METHOD_MPESA_MANUAL.equals(paymentMethod)) {
            return ledgerByCode(businessId, LedgerAccountCodes.MPESA_CLEARING);
        }
        return ledgerByCode(businessId, LedgerAccountCodes.BANK_ACCOUNT);
    }

    private LedgerAccount ledgerByCode(String businessId, String code) {
        return ledgerAccountRepository.findByBusinessIdAndCode(businessId, code)
                .orElseThrow(() -> new IllegalStateException("Missing ledger account " + code));
    }

    private static void assertBalanced(List<JournalLine> lines) {
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (JournalLine l : lines) {
            dr = dr.add(l.getDebit());
            cr = cr.add(l.getCredit());
        }
        if (dr.subtract(cr).abs().compareTo(MONEY_TOL) > 0) {
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

    private static ExpenseResponse toDto(Expense e) {
        return new ExpenseResponse(
                e.getId(),
                e.getBranchId(),
                e.getExpenseDate(),
                e.getName(),
                e.getCategoryType(),
                e.getAmount(),
                e.getPaymentMethod(),
                e.isIncludeInCashDrawer(),
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

