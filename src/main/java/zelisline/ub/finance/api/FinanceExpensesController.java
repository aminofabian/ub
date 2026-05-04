package zelisline.ub.finance.api;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.api.dto.ExpenseResponse;
import zelisline.ub.finance.api.dto.PostExpenseRequest;
import zelisline.ub.finance.application.ExpenseService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/finance/expenses")
@RequiredArgsConstructor
public class FinanceExpensesController {

    private final ExpenseService expenseService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'finance.expenses.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseResponse recordExpense(
            @Valid @RequestBody PostExpenseRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return expenseService.recordExpense(
                TenantRequestIds.resolveBusinessId(request),
                body,
                user.userId(),
                idempotencyKey
        );
    }

    @GetMapping("/{expenseId}")
    @PreAuthorize("hasPermission(null, 'finance.expenses.read')")
    public ExpenseResponse getExpense(@PathVariable String expenseId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return expenseService.getExpense(TenantRequestIds.resolveBusinessId(request), expenseId);
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'finance.expenses.read')")
    public List<ExpenseResponse> listExpenses(
            @RequestParam(required = false) LocalDate date,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return expenseService.listExpensesForDate(TenantRequestIds.resolveBusinessId(request), date);
    }
}

