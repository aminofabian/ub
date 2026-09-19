package zelisline.ub.payments.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.PlatformCustodySettlementResponse;
import zelisline.ub.payments.api.dto.PlatformMpesaCustodySettingsResponse;
import zelisline.ub.payments.api.dto.UpdatePlatformMpesaCustodySettingsRequest;
import zelisline.ub.payments.application.PlatformCustodySettlementService;
import zelisline.ub.payments.application.PlatformMpesaCustodySettingsService;

@RestController
@RequestMapping("/api/v1/super-admin/payments/mpesa-custody")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminMpesaCustodyController {

    private final PlatformMpesaCustodySettingsService service;
    private final PlatformCustodySettlementService settlementService;

    @GetMapping
    public PlatformMpesaCustodySettingsResponse get() {
        return service.getForSuperAdmin();
    }

    @PatchMapping
    public PlatformMpesaCustodySettingsResponse update(
            @Valid @RequestBody UpdatePlatformMpesaCustodySettingsRequest body) {
        return service.update(body);
    }

    /** Recent custody settlements across all tenants (ops visibility into stuck/failed payouts). */
    @GetMapping("/settlements")
    public List<PlatformCustodySettlementResponse> settlements(
            @RequestParam(defaultValue = "50") int limit
    ) {
        return settlementService.listForSuperAdmin(limit);
    }

    /** Retry a FAILED settlement on the same rail (provider declined). */
    @PostMapping("/settlements/{id}/retry")
    public PlatformCustodySettlementResponse retry(@PathVariable String id) {
        return settlementService.retry(id);
    }
}
