package zelisline.ub.storefront.api;

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
import zelisline.ub.storefront.api.dto.ShopperAccountOverviewResponse;
import zelisline.ub.storefront.api.dto.WebOrderDetailResponse;
import zelisline.ub.storefront.application.ShopperAccountService;
import zelisline.ub.storefront.application.WebOrderAdminService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/** Self-service storefront hub (pickup history + optional wallet / loyalty linkage). */
@Validated
@RestController
@RequestMapping("/api/v1/me/shopper")
@RequiredArgsConstructor
public class ShopperMeController {

    private final ShopperAccountService shopperAccountService;
    private final WebOrderAdminService webOrderAdminService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ShopperAccountOverviewResponse overview(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size
    ) {
        var principal = CurrentTenantUser.requireHuman(request);
        String tenant = TenantRequestIds.resolveBusinessId(request);
        return shopperAccountService.overview(tenant, principal.userId(), page, size);
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("isAuthenticated()")
    public WebOrderDetailResponse orderDetail(
            @PathVariable String orderId,
            HttpServletRequest request
    ) {
        var principal = CurrentTenantUser.requireHuman(request);
        String tenant = TenantRequestIds.resolveBusinessId(request);
        String emailNorm = shopperAccountService.normalizedEmailForUser(tenant, principal.userId());
        return webOrderAdminService.getOrderForShopperEmail(tenant, orderId.trim(), emailNorm);
    }
}
