package zelisline.ub.credits.api;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.CreditsActivitySummaryResponse;
import zelisline.ub.credits.application.CreditsActivitySummaryService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/credits")
@RequiredArgsConstructor
public class CreditsActivitySummaryController {

    private final CreditsActivitySummaryService creditsActivitySummaryService;

    /**
     * Paid collections for the date range plus live total owed across open tabs.
     */
    @GetMapping("/activity-summary")
    @PreAuthorize(
            "hasPermission(null, 'sales.intelligence.read') or hasPermission(null, 'credits.customers.read')")
    public CreditsActivitySummaryResponse activitySummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return creditsActivitySummaryService.summarize(
                TenantRequestIds.resolveBusinessId(request), from, to);
    }
}
