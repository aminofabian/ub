package zelisline.ub.purchasing.api;

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
import zelisline.ub.purchasing.api.dto.PriceCompetitivenessRow;
import zelisline.ub.purchasing.api.dto.SingleSourceRiskRow;
import zelisline.ub.purchasing.api.dto.SpendBySupplierCategoryRow;
import zelisline.ub.purchasing.application.SupplierIntelligenceService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/purchasing/intelligence")
@RequiredArgsConstructor
public class SupplierIntelligenceController {

    private final SupplierIntelligenceService supplierIntelligenceService;

    @GetMapping("/spend-by-supplier-category")
    @PreAuthorize("hasPermission(null, 'purchasing.intelligence.read')")
    public List<SpendBySupplierCategoryRow> spendBySupplierCategory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplierIntelligenceService.spendBySupplierAndCategory(
                TenantRequestIds.resolveBusinessId(request), from, to);
    }

    @GetMapping("/price-competitiveness")
    @PreAuthorize("hasPermission(null, 'purchasing.intelligence.read')")
    public List<PriceCompetitivenessRow> priceCompetitiveness(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplierIntelligenceService.priceCompetitivenessVsPrimary(
                TenantRequestIds.resolveBusinessId(request), from, to);
    }

    @GetMapping("/single-source-risk")
    @PreAuthorize("hasPermission(null, 'purchasing.intelligence.read')")
    public List<SingleSourceRiskRow> singleSourceRisk(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return supplierIntelligenceService.singleSourceRiskItems(TenantRequestIds.resolveBusinessId(request));
    }
}
