package zelisline.ub.finance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.FinanceConstants;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.api.dto.ExpenseScheduleResponse;
import zelisline.ub.finance.api.dto.PatchExpenseScheduleRequest;
import zelisline.ub.finance.api.dto.PostExpenseScheduleRequest;
import zelisline.ub.finance.domain.ExpenseSchedule;
import zelisline.ub.finance.domain.LedgerAccount;
import zelisline.ub.finance.repository.ExpenseScheduleRepository;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class ExpenseScheduleService {

    private final ExpenseScheduleRepository expenseScheduleRepository;
    private final LedgerBootstrapService ledgerBootstrapService;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final BranchRepository branchRepository;

    @Transactional
    public ExpenseScheduleResponse create(String businessId, PostExpenseScheduleRequest req, String userId) {
        ledgerBootstrapService.ensureStandardAccounts(businessId);
        ExpenseSchedule s = new ExpenseSchedule();
        s.setBusinessId(businessId);
        s.setBranchId(validateBranch(businessId, req.branchId()));
        s.setName(requireName(req.name()));
        s.setCategoryType(requireCategory(req.categoryType()));
        s.setAmount(requireAmount(req.amount()));
        s.setPaymentMethod(requirePaymentMethod(req.paymentMethod()));
        s.setFrequency(requireFrequency(req.frequency()));
        s.setStartDate(requireStartDate(req.startDate()));
        if (req.endDate() != null && req.endDate().isBefore(req.startDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endDate cannot be before startDate");
        }
        s.setEndDate(req.endDate());
        s.setActive(true);
        s.setIncludeInCashDrawer(req.includeInCashDrawer() != null && req.includeInCashDrawer());
        s.setReceiptS3Key(blankToNull(req.receiptS3Key()));
        s.setExpenseLedgerAccountId(resolveExpenseLedger(businessId, req.expenseLedgerAccountId()).getId());
        s.setCreatedBy(userId);
        return toDto(expenseScheduleRepository.save(s));
    }

    @Transactional
    public ExpenseScheduleResponse update(String businessId, String scheduleId, PatchExpenseScheduleRequest req) {
        ExpenseSchedule s = expenseScheduleRepository.findByIdAndBusinessId(scheduleId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense schedule not found"));
        if (req.endDate() != null) {
            if (req.endDate().isBefore(s.getStartDate())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endDate cannot be before startDate");
            }
            s.setEndDate(req.endDate());
        }
        if (req.active() != null) {
            s.setActive(req.active());
        }
        return toDto(expenseScheduleRepository.save(s));
    }

    @Transactional(readOnly = true)
    public List<ExpenseScheduleResponse> listActive(String businessId) {
        return expenseScheduleRepository.findByBusinessIdAndActiveTrue(businessId)
                .stream()
                .map(ExpenseScheduleService::toDto)
                .toList();
    }

    @Transactional
    public ExpenseScheduleResponse deactivate(String businessId, String scheduleId) {
        ExpenseSchedule s = expenseScheduleRepository.findByIdAndBusinessId(scheduleId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Expense schedule not found"));
        s.setActive(false);
        return toDto(expenseScheduleRepository.save(s));
    }

    private LedgerAccount resolveExpenseLedger(String businessId, String preferredId) {
        if (preferredId == null || preferredId.isBlank()) {
            return ledgerByCode(businessId, LedgerAccountCodes.OPERATING_EXPENSES);
        }
        LedgerAccount account = ledgerAccountRepository.findById(preferredId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expense ledger account not found"));
        if (!businessId.equals(account.getBusinessId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expense ledger account not in business");
        }
        if (!"expense".equalsIgnoreCase(account.getAccountType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expense ledger account must be expense type");
        }
        return account;
    }

    private LedgerAccount ledgerByCode(String businessId, String code) {
        return ledgerAccountRepository.findByBusinessIdAndCode(businessId, code)
                .orElseThrow(() -> new IllegalStateException("Missing ledger account " + code));
    }

    private String validateBranch(String businessId, String branchId) {
        String value = blankToNull(branchId);
        if (value == null) {
            return null;
        }
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(value, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
        return value;
    }

    private static String requireName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        return value;
    }

    private static BigDecimal requireAmount(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
        if (value.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be > 0");
        }
        return value;
    }

    private static String requireCategory(String category) {
        String value = normalized(category);
        if (!FinanceConstants.EXPENSE_CATEGORY_FIXED.equals(value)
                && !FinanceConstants.EXPENSE_CATEGORY_VARIABLE.equals(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "categoryType must be fixed or variable");
        }
        return value;
    }

    private static String requirePaymentMethod(String paymentMethod) {
        String value = normalized(paymentMethod);
        if (!FinanceConstants.EXPENSE_PAY_METHOD_CASH.equals(value)
                && !FinanceConstants.EXPENSE_PAY_METHOD_MPESA_MANUAL.equals(value)
                && !FinanceConstants.EXPENSE_PAY_METHOD_BANK.equals(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "paymentMethod must be cash, mpesa_manual, or bank");
        }
        return value;
    }

    private static String requireFrequency(String frequency) {
        String value = normalized(frequency);
        if (!FinanceConstants.EXPENSE_FREQUENCY_DAILY.equals(value)
                && !FinanceConstants.EXPENSE_FREQUENCY_WEEKLY.equals(value)
                && !FinanceConstants.EXPENSE_FREQUENCY_MONTHLY.equals(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "frequency must be daily, weekly, or monthly");
        }
        return value;
    }

    private static LocalDate requireStartDate(LocalDate startDate) {
        if (startDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate is required");
        }
        return startDate;
    }

    private static ExpenseScheduleResponse toDto(ExpenseSchedule s) {
        return new ExpenseScheduleResponse(
                s.getId(),
                s.getBranchId(),
                s.getName(),
                s.getCategoryType(),
                s.getAmount(),
                s.getPaymentMethod(),
                s.getFrequency(),
                s.getStartDate(),
                s.getEndDate(),
                s.isActive(),
                s.isIncludeInCashDrawer(),
                s.getReceiptS3Key(),
                s.getExpenseLedgerAccountId(),
                s.getLastGeneratedOn(),
                s.getCreatedBy()
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

