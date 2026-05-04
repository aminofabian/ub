package zelisline.ub.reporting.api;

import java.time.LocalDate;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.reporting.api.dto.SupplierMonthlySpendResponse;
import zelisline.ub.reporting.application.SupplierReportsService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/reports/suppliers")
@RequiredArgsConstructor
public class SupplierReportsController {

    private final SupplierReportsService supplierReportsService;

    @GetMapping("/monthly-spend")
    @PreAuthorize("hasPermission(null, 'reports.suppliers.read')")
    public SupplierMonthlySpendResponse monthlySpend(
            @RequestParam("fromMonth") LocalDate fromMonth,
            @RequestParam("toMonth") LocalDate toMonth,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplierReportsService.monthlySpend(
                TenantRequestIds.resolveBusinessId(request), fromMonth, toMonth);
    }
}
