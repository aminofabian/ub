package zelisline.ub.marketplace.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.MarketplaceConnectResponse;
import zelisline.ub.marketplace.api.dto.MarketplaceSupplierDetailResponse;
import zelisline.ub.marketplace.application.MarketplaceConnectService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/marketplace/suppliers")
@RequiredArgsConstructor
public class MarketplaceSupplierController {

    private final MarketplaceConnectService marketplaceConnectService;

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'marketplace.suppliers.read')")
    public MarketplaceSupplierDetailResponse get(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return marketplaceConnectService.getSupplierDetail(id);
    }

    @PostMapping("/{id}/connect")
    @PreAuthorize("hasPermission(null, 'marketplace.suppliers.connect')")
    @ResponseStatus(HttpStatus.CREATED)
    public MarketplaceConnectResponse connect(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return marketplaceConnectService.connect(TenantRequestIds.resolveBusinessId(request), id);
    }
}
