package zelisline.ub.payments.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.GatewayStkPushOpsResponse;
import zelisline.ub.payments.application.SuperAdminStkPushService;

@RestController
@RequestMapping("/api/v1/super-admin/payments/stk-pushes")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminStkPushController {

    private final SuperAdminStkPushService service;

    /** Recent STK pushes across all tenants (ops visibility into prompts and settlements). */
    @GetMapping
    public List<GatewayStkPushOpsResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return service.listForSuperAdmin(status, limit);
    }
}
