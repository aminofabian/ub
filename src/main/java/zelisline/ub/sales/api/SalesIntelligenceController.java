package zelisline.ub.sales.api;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.sales.api.dto.CategoryDailyRevenueRow;
import zelisline.ub.sales.api.dto.ItemRevenueRow;
import zelisline.ub.sales.api.dto.PaymentLedgerRow;
import zelisline.ub.sales.api.dto.PaymentMethodBreakdownRow;
import zelisline.ub.sales.api.dto.RecentSaleRow;
import zelisline.ub.sales.api.dto.RevenueByCategoryRow;
import zelisline.ub.sales.api.dto.StaffPerformanceRow;
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
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String itemTypeId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return salesIntelligenceService.netRevenueByCategory(
                TenantRequestIds.resolveBusinessId(request), from, to, categoryId, branchId, itemTypeId);
    }

    @GetMapping("/revenue-by-category/{categoryId}/daily")
    @PreAuthorize("hasPermission(null, 'sales.intelligence.read')")
    public List<CategoryDailyRevenueRow> dailyRevenueByCategory(
            @PathVariable String categoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return salesIntelligenceService.dailyRevenueByCategory(
                TenantRequestIds.resolveBusinessId(request), categoryId, from, to);
    }

    @GetMapping("/revenue-by-category/{categoryId}/items")
    @PreAuthorize("hasPermission(null, 'sales.intelligence.read')")
    public List<ItemRevenueRow> revenueByCategoryItems(
            @PathVariable String categoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return salesIntelligenceService.revenueByCategoryItems(
                TenantRequestIds.resolveBusinessId(request), categoryId, from, to);
    }

    @GetMapping("/recent-web-order-lines")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public List<RecentSaleRow> recentWebOrderLines(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return salesIntelligenceService.recentWebOrderLines(
                TenantRequestIds.resolveBusinessId(request), from, to, branchId);
    }

    @GetMapping("/recent-sales")
    @PreAuthorize("hasPermission(null, 'sales.intelligence.read')")
    public List<RecentSaleRow> recentSales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String itemTypeId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return salesIntelligenceService.recentSales(
                TenantRequestIds.resolveBusinessId(request), from, to, branchId, itemTypeId);
    }

    @GetMapping("/payments-by-method")
    @PreAuthorize("hasPermission(null, 'sales.intelligence.read')")
    public List<PaymentMethodBreakdownRow> paymentsByMethod(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String itemTypeId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return salesIntelligenceService.paymentsByMethod(
                TenantRequestIds.resolveBusinessId(request), from, to, branchId, itemTypeId);
    }

    /** Chronological tender lines for reconciling a day's cash / M-Pesa / credit take. */
    @GetMapping("/payment-ledger")
    @PreAuthorize("hasPermission(null, 'sales.intelligence.read')")
    public List<PaymentLedgerRow> paymentLedger(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return salesIntelligenceService.paymentLedger(
                TenantRequestIds.resolveBusinessId(request), from, to, branchId);
    }

    @GetMapping("/staff-performance")
    @PreAuthorize("hasPermission(null, 'sales.intelligence.read')")
    public List<StaffPerformanceRow> staffPerformance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String itemTypeId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return salesIntelligenceService.staffPerformance(
                TenantRequestIds.resolveBusinessId(request), from, to, branchId, itemTypeId);
    }
}
