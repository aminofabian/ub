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
import zelisline.ub.payroll.api.dto.PayRunRequest;
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
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return payrollService.previewRun(TenantRequestIds.resolveBusinessId(request), year, month);
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
