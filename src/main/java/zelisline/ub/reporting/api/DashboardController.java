package zelisline.ub.reporting.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.reporting.api.dto.OwnerDashboardResponse;
import zelisline.ub.reporting.application.DashboardService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final MeterRegistry meterRegistry;
    private final Timer ownerSummaryTimer;

    public DashboardController(DashboardService dashboardService, MeterRegistry meterRegistry) {
        this.dashboardService = dashboardService;
        this.meterRegistry = meterRegistry;
        this.ownerSummaryTimer = Timer.builder("dashboard.owner.summary").register(meterRegistry);
    }

    @GetMapping("/owner-summary")
    @PreAuthorize("hasPermission(null, 'finance.reports.read')")
    @ResponseStatus(HttpStatus.OK)
    public OwnerDashboardResponse ownerSummary(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return dashboardService.ownerSummary(TenantRequestIds.resolveBusinessId(request));
        } finally {
            sample.stop(ownerSummaryTimer);
        }
    }
}
