package zelisline.ub.payroll.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payroll.api.dto.PayAllRunRequest;
import zelisline.ub.payroll.api.dto.PayAllRunResponse;
import zelisline.ub.payroll.api.dto.PayRunRequest;
import zelisline.ub.payroll.api.dto.PayrollAdvanceLedgerRowResponse;
import zelisline.ub.payroll.api.dto.PayrollCalendarResponse;
import zelisline.ub.payroll.api.dto.PayrollRunRowResponse;
import zelisline.ub.payroll.api.dto.PayslipResponse;
import zelisline.ub.payroll.application.PayrollService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/payroll")
@RequiredArgsConstructor
public class PayrollRunController {

    private final PayrollService payrollService;

    @GetMapping("/runs")
    @PreAuthorize("hasPermission(null, 'payroll.view')")
    public List<PayrollRunRowResponse> previewRun(
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(required = false) String branchId,
            @RequestParam(defaultValue = "false") boolean statutory,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return payrollService.previewRun(
                TenantRequestIds.resolveBusinessId(request),
                year,
                month,
                branchId,
                statutory
        );
    }

    @GetMapping("/advances")
    @PreAuthorize("hasPermission(null, 'payroll.view')")
    public List<PayrollAdvanceLedgerRowResponse> listAdvances(
            @RequestParam(required = false) String status,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return payrollService.listBusinessAdvances(
                TenantRequestIds.resolveBusinessId(request),
                status
        );
    }

    @GetMapping("/payslips")
    @PreAuthorize("hasPermission(null, 'payroll.view')")
    public List<PayslipResponse> listPeriodPayslips(
            @RequestParam int year,
            @RequestParam int month,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return payrollService.listPeriodPayslips(
                TenantRequestIds.resolveBusinessId(request),
                year,
                month
        );
    }

    @GetMapping("/calendar")
    @PreAuthorize("hasPermission(null, 'payroll.view')")
    public PayrollCalendarResponse calendar(
            @RequestParam int year,
            @RequestParam(required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return payrollService.calendarYear(
                TenantRequestIds.resolveBusinessId(request),
                year,
                branchId
        );
    }

    @PostMapping("/runs/pay-all")
    @PreAuthorize("hasPermission(null, 'payroll.run')")
    public PayAllRunResponse payAll(
            @Valid @RequestBody PayAllRunRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return payrollService.payAll(
                TenantRequestIds.resolveBusinessId(request),
                body,
                user.userId()
        );
    }

    @PostMapping("/runs/{userId}/pay")
    @PreAuthorize("hasPermission(null, 'payroll.run')")
    @ResponseStatus(HttpStatus.CREATED)
    public PayslipResponse pay(
            @PathVariable String userId,
            @Valid @RequestBody PayRunRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return payrollService.pay(
                TenantRequestIds.resolveBusinessId(request),
                userId,
                body,
                user.userId()
        );
    }
}
