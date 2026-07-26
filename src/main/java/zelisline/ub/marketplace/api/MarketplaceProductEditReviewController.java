package zelisline.ub.marketplace.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.MarketplaceProductEditRequestRow;
import zelisline.ub.marketplace.api.dto.ReviewMarketplaceProductEditRequest;
import zelisline.ub.marketplace.application.MarketplaceProductEditReviewService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/marketplace/product-edit-requests")
@RequiredArgsConstructor
public class MarketplaceProductEditReviewController {

    private final MarketplaceProductEditReviewService reviewService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'marketplace.suppliers.connect')")
    public List<MarketplaceProductEditRequestRow> listPending(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return reviewService.listPendingForBusiness(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping("/{editId}/approve")
    @PreAuthorize("hasPermission(null, 'marketplace.suppliers.connect')")
    public MarketplaceProductEditRequestRow approve(
            @PathVariable String editId,
            @Valid @RequestBody(required = false) ReviewMarketplaceProductEditRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return reviewService.approve(
                TenantRequestIds.resolveBusinessId(request),
                principal.userId(),
                editId,
                body == null ? new ReviewMarketplaceProductEditRequest(null) : body);
    }

    @PostMapping("/{editId}/reject")
    @PreAuthorize("hasPermission(null, 'marketplace.suppliers.connect')")
    public MarketplaceProductEditRequestRow reject(
            @PathVariable String editId,
            @Valid @RequestBody(required = false) ReviewMarketplaceProductEditRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return reviewService.reject(
                TenantRequestIds.resolveBusinessId(request),
                principal.userId(),
                editId,
                body == null ? new ReviewMarketplaceProductEditRequest(null) : body);
    }
}
