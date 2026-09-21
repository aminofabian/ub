package zelisline.ub.finance.api;

import java.time.LocalDate;

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
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.api.dto.ExpenseKopokopoPayResponse;
import zelisline.ub.finance.api.dto.ExpenseListResponse;
import zelisline.ub.finance.api.dto.ExpensePayOptionsResponse;
import zelisline.ub.finance.api.dto.ExpenseResponse;
import zelisline.ub.finance.api.dto.PostExpenseRequest;
import zelisline.ub.finance.application.ExpenseDisbursementService;
import zelisline.ub.finance.application.ExpenseService;
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.till.application.TillDeviceService;

@Validated
@RestController
@RequestMapping("/api/v1/finance/expenses")
@RequiredArgsConstructor
public class FinanceExpensesController {

    private static final String PERM_MANAGE = "finance.expenses.manage";

    private final ExpenseService expenseService;
    private final ExpenseDisbursementService expenseDisbursementService;
    private final RequestPermissionService requestPermissionService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'finance.expenses.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseResponse recordExpense(
            @Valid @RequestBody PostExpenseRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        boolean canManage = requestPermissionService.hasPermission(user.roleId(), PERM_MANAGE);
        return expenseService.recordExpense(
                TenantRequestIds.resolveBusinessId(request),
                body,
                user.userId(),
                idempotencyKey,
                request.getHeader(TillDeviceService.TILL_DEVICE_HEADER),
                canManage
        );
    }

    @PostMapping("/{expenseId}/approve")
    @PreAuthorize("hasPermission(null, 'finance.expenses.manage')")
    public ExpenseResponse approveExpense(@PathVariable String expenseId, HttpServletRequest request) {
        var user = CurrentTenantUser.requireHuman(request);
        return expenseService.approveExpense(
                TenantRequestIds.resolveBusinessId(request),
                expenseId,
                user.userId()
        );
    }

    @PostMapping("/{expenseId}/reject")
    @PreAuthorize("hasPermission(null, 'finance.expenses.manage')")
    public ExpenseResponse rejectExpense(@PathVariable String expenseId, HttpServletRequest request) {
        var user = CurrentTenantUser.requireHuman(request);
        return expenseService.rejectExpense(
                TenantRequestIds.resolveBusinessId(request),
                expenseId,
                user.userId()
        );
    }

    @GetMapping("/{expenseId}")
    @PreAuthorize("hasPermission(null, 'finance.expenses.read')")
    public ExpenseResponse getExpense(@PathVariable String expenseId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return expenseService.getExpense(TenantRequestIds.resolveBusinessId(request), expenseId);
    }

    /**
     * Day list ({@code date=}) for the day ledger, or period list ({@code from}+{@code to})
     * for the expenses hub. Period mode returns {@link ExpenseListResponse}; day mode keeps
     * the legacy bare array so existing clients do not break.
     */
    @GetMapping
    @PreAuthorize("hasPermission(null, 'finance.expenses.read')")
    public Object listExpenses(
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String categoryType,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String approvalStatus,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        boolean period = from != null || to != null;
        if (period) {
            if (date != null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Pass either date= or from=/to=, not both");
            }
            return expenseService.listExpensesForPeriod(
                    businessId, from, to, branchId, categoryType, categoryCode, source, approvalStatus, q, page, size);
        }
        if (branchId != null || categoryType != null || categoryCode != null || source != null
                || approvalStatus != null || q != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "branchId, categoryType, categoryCode, source, approvalStatus, and q require from= and to=");
        }
        return expenseService.listExpensesForDate(businessId, date);
    }

    @GetMapping("/{expenseId}/pay-options")
    @PreAuthorize("hasPermission(null, 'finance.expenses.read')")
    public ExpensePayOptionsResponse payOptions(@PathVariable String expenseId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return expenseDisbursementService.payOptions(
                TenantRequestIds.resolveBusinessId(request), expenseId);
    }

    @PostMapping("/{expenseId}/pay/kopokopo")
    @PreAuthorize("hasPermission(null, 'finance.expenses.manage')")
    public ExpenseKopokopoPayResponse initiateKopokopoPay(
            @PathVariable String expenseId,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return expenseDisbursementService.initiate(
                TenantRequestIds.resolveBusinessId(request),
                expenseId,
                user.userId());
    }

    @GetMapping("/{expenseId}/pay/kopokopo")
    @PreAuthorize("hasPermission(null, 'finance.expenses.read')")
    public ExpenseKopokopoPayResponse kopokopoPayStatus(
            @PathVariable String expenseId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return expenseDisbursementService.status(
                TenantRequestIds.resolveBusinessId(request), expenseId);
    }

    @PostMapping("/{expenseId}/pay/kopokopo/cancel")
    @PreAuthorize("hasPermission(null, 'finance.expenses.manage')")
    public ExpenseKopokopoPayResponse cancelKopokopoPay(
            @PathVariable String expenseId,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return expenseDisbursementService.cancel(
                TenantRequestIds.resolveBusinessId(request),
                expenseId,
                user.userId());
    }
}
