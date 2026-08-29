package zelisline.ub.payroll.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
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
import zelisline.ub.payroll.api.dto.CreateSalaryAdvanceRequest;
import zelisline.ub.payroll.api.dto.CreateSalaryRequest;
import zelisline.ub.payroll.api.dto.PatchSalaryAdvanceRequest;
import zelisline.ub.payroll.api.dto.PayslipResponse;
import zelisline.ub.payroll.api.dto.SalaryAdvanceResponse;
import zelisline.ub.payroll.api.dto.SalaryResponse;
import zelisline.ub.payroll.application.PayrollService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/staff")
@RequiredArgsConstructor
public class StaffPayrollController {

    private final PayrollService payrollService;

    @GetMapping("/{userId}/salaries")
    @PreAuthorize("hasPermission(null, 'payroll.view')")
    public List<SalaryResponse> listSalaries(@PathVariable String userId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return payrollService.listSalaries(TenantRequestIds.resolveBusinessId(request), userId);
    }

    @PostMapping("/{userId}/salaries")
    @PreAuthorize("hasPermission(null, 'payroll.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public SalaryResponse addSalary(
            @PathVariable String userId,
            @Valid @RequestBody CreateSalaryRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return payrollService.addSalary(
                TenantRequestIds.resolveBusinessId(request),
                userId,
                body,
                user.userId()
        );
    }

    @GetMapping("/{userId}/advances")
    @PreAuthorize("hasPermission(null, 'payroll.view')")
    public List<SalaryAdvanceResponse> listAdvances(@PathVariable String userId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return payrollService.listAdvances(TenantRequestIds.resolveBusinessId(request), userId);
    }

    @PostMapping("/{userId}/advances")
    @PreAuthorize("hasPermission(null, 'payroll.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public SalaryAdvanceResponse addAdvance(
            @PathVariable String userId,
            @Valid @RequestBody CreateSalaryAdvanceRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return payrollService.addAdvance(
                TenantRequestIds.resolveBusinessId(request),
                userId,
                body,
                user.userId()
        );
    }

    @PatchMapping("/{userId}/advances/{advanceId}")
    @PreAuthorize("hasPermission(null, 'payroll.manage')")
    public SalaryAdvanceResponse patchAdvance(
            @PathVariable String userId,
            @PathVariable String advanceId,
            @Valid @RequestBody PatchSalaryAdvanceRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return payrollService.patchAdvance(
                TenantRequestIds.resolveBusinessId(request),
                userId,
                advanceId,
                body
        );
    }

    @GetMapping("/{userId}/payslips")
    @PreAuthorize("hasPermission(null, 'payroll.view')")
    public List<PayslipResponse> listPayslips(@PathVariable String userId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return payrollService.listPayslips(TenantRequestIds.resolveBusinessId(request), userId);
    }
}
