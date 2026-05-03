package zelisline.ub.sales.api;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.sales.api.dto.RevenueByCategoryRow;
import zelisline.ub.sales.application.SalesIntelligenceService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/sales/intelligence")
@RequiredArgsConstructor
public class SalesIntelligenceController {

    private final SalesIntelligenceService salesIntelligenceService;

    @GetMapping("/revenue-by-category")
    @PreAuthorize("hasPermission(null, 'sales.intelligence.read')")
    public List<RevenueByCategoryRow> revenueByCategory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return salesIntelligenceService.netRevenueByCategory(
                TenantRequestIds.resolveBusinessId(request), from, to);
    }
}
