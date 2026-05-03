package zelisline.ub.storefront.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.storefront.api.dto.WebOrderDetailResponse;
import zelisline.ub.storefront.api.dto.WebOrderSummaryResponse;
import zelisline.ub.storefront.application.WebOrderAdminService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/web-orders")
@RequiredArgsConstructor
public class WebOrdersController {

    private final WebOrderAdminService webOrderAdminService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public Page<WebOrderSummaryResponse> list(
            @PageableDefault(size = 50) Pageable pageable,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return webOrderAdminService.pageOrders(TenantRequestIds.resolveBusinessId(request), pageable);
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public WebOrderDetailResponse detail(@PathVariable String orderId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return webOrderAdminService.getOrder(TenantRequestIds.resolveBusinessId(request), orderId.trim());
    }
}
