package zelisline.ub.payroll.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payroll.api.dto.StaffPaySelfResponse;
import zelisline.ub.payroll.application.PayrollService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/payroll")
@RequiredArgsConstructor
public class PayrollSelfController {

    private final PayrollService payrollService;

    @GetMapping("/me")
    @PreAuthorize("hasPermission(null, 'payroll.self.read')")
    public StaffPaySelfResponse selfPortal(HttpServletRequest request) {
        var user = CurrentTenantUser.requireHuman(request);
        return payrollService.getSelfPortal(
                TenantRequestIds.resolveBusinessId(request),
                user.userId()
        );
    }
}
