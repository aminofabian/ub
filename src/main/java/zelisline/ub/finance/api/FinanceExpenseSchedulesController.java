package zelisline.ub.finance.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.api.dto.ExpenseScheduleResponse;
import zelisline.ub.finance.api.dto.PatchExpenseScheduleRequest;
import zelisline.ub.finance.api.dto.PostExpenseScheduleRequest;
import zelisline.ub.finance.application.ExpenseScheduleService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/finance/expense-schedules")
@RequiredArgsConstructor
public class FinanceExpenseSchedulesController {

    private final ExpenseScheduleService expenseScheduleService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'finance.expenses.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseScheduleResponse create(
            @Valid @RequestBody PostExpenseScheduleRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.require(request);
        return expenseScheduleService.create(
                TenantRequestIds.resolveBusinessId(request),
                body,
                user.userId()
        );
    }

    @PatchMapping("/{scheduleId}")
    @PreAuthorize("hasPermission(null, 'finance.expenses.write')")
    public ExpenseScheduleResponse update(
            @PathVariable String scheduleId,
            @RequestBody PatchExpenseScheduleRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return expenseScheduleService.update(TenantRequestIds.resolveBusinessId(request), scheduleId, body);
    }

    @DeleteMapping("/{scheduleId}")
    @PreAuthorize("hasPermission(null, 'finance.expenses.write')")
    public ExpenseScheduleResponse deactivate(@PathVariable String scheduleId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return expenseScheduleService.deactivate(TenantRequestIds.resolveBusinessId(request), scheduleId);
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'finance.expenses.read')")
    public List<ExpenseScheduleResponse> list(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return expenseScheduleService.listActive(TenantRequestIds.resolveBusinessId(request));
    }
}

