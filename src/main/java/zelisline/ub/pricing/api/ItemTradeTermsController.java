package zelisline.ub.pricing.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.pricing.api.dto.ItemTradeTermsDto;
import zelisline.ub.pricing.application.ItemTradeTermsService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/** Minimum order quantity and quantity prices for one product of the signed-in shop. */
@Validated
@RestController
@RequestMapping("/api/v1/items/{itemId}/trade-terms")
@RequiredArgsConstructor
public class ItemTradeTermsController {

    private final ItemTradeTermsService tradeTermsService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public ItemTradeTermsDto get(@PathVariable String itemId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return tradeTermsService.get(TenantRequestIds.resolveBusinessId(request), itemId);
    }

    @PutMapping
    @PreAuthorize("hasPermission(null, 'pricing.sell_price.set')")
    public ItemTradeTermsDto replace(
            @PathVariable String itemId,
            @Valid @RequestBody ItemTradeTermsDto body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return tradeTermsService.replace(TenantRequestIds.resolveBusinessId(request), itemId, body);
    }
}
