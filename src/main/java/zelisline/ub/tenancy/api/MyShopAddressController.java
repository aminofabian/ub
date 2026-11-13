package zelisline.ub.tenancy.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.dto.ChangeShopAddressRequest;
import zelisline.ub.tenancy.api.dto.ShopAddressChangeResponse;
import zelisline.ub.tenancy.api.dto.ShopAddressCheckResponse;
import zelisline.ub.tenancy.api.dto.ShopAddressStatusResponse;
import zelisline.ub.tenancy.application.ShopAddressService;

/**
 * Lets a shop change its free platform address. Same permission as the rest of the domains
 * screen; every call is scoped to the signed-in business.
 */
@Validated
@RestController
@RequestMapping("/api/v1/businesses/me/address")
public class MyShopAddressController {

    private static final String REQUIRES_MANAGE_SETTINGS =
            "hasPermission(null, 'business.manage_settings')";

    private final ShopAddressService shopAddressService;

    public MyShopAddressController(ShopAddressService shopAddressService) {
        this.shopAddressService = shopAddressService;
    }

    @GetMapping
    @PreAuthorize(REQUIRES_MANAGE_SETTINGS)
    public ShopAddressStatusResponse status(HttpServletRequest request) {
        return shopAddressService.status(TenantRequestIds.resolveBusinessId(request));
    }

    @GetMapping("/check")
    @PreAuthorize(REQUIRES_MANAGE_SETTINGS)
    public ShopAddressCheckResponse check(
            HttpServletRequest request,
            @RequestParam("name") String name
    ) {
        return shopAddressService.check(TenantRequestIds.resolveBusinessId(request), name);
    }

    @PostMapping
    @PreAuthorize(REQUIRES_MANAGE_SETTINGS)
    public ShopAddressChangeResponse change(
            HttpServletRequest request,
            @Valid @RequestBody ChangeShopAddressRequest body
    ) {
        return shopAddressService.change(
                TenantRequestIds.resolveBusinessId(request),
                body.name(),
                CurrentTenantUser.auditActorId(request));
    }
}
