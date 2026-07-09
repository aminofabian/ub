package zelisline.ub.marketplace.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.CreateMarketplaceSupplierRequest;
import zelisline.ub.marketplace.api.dto.CreateMarketplaceSupplierUserRequest;
import zelisline.ub.marketplace.api.dto.MarketplaceSupplierSummaryResponse;
import zelisline.ub.marketplace.application.MarketplaceAdminService;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin/marketplace/suppliers")
@RequiredArgsConstructor
public class SuperAdminMarketplaceController {

    private final MarketplaceAdminService marketplaceAdminService;

    @GetMapping
    public Page<MarketplaceSupplierSummaryResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            Pageable pageable) {
        return marketplaceAdminService.listSuppliers(q, status, pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MarketplaceSupplierSummaryResponse create(@Valid @RequestBody CreateMarketplaceSupplierRequest request) {
        return marketplaceAdminService.createSupplier(request);
    }

    @PostMapping("/{id}/activate")
    public MarketplaceSupplierSummaryResponse activate(@PathVariable String id) {
        return marketplaceAdminService.activateSupplier(id);
    }

    @PostMapping("/{id}/users")
    @ResponseStatus(HttpStatus.CREATED)
    public void createUser(
            @PathVariable String id,
            @Valid @RequestBody CreateMarketplaceSupplierUserRequest request) {
        marketplaceAdminService.createPortalUser(id, request);
    }
}
