package zelisline.ub.pricing.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.pricing.api.dto.BuyingPriceResponse;
import zelisline.ub.pricing.api.dto.PostBuyingPriceRequest;
import zelisline.ub.pricing.api.dto.PostPriceRuleRequest;
import zelisline.ub.pricing.api.dto.PostSellingPriceRequest;
import zelisline.ub.pricing.api.dto.PostTaxRateRequest;
import zelisline.ub.pricing.api.dto.PriceRuleResponse;
import zelisline.ub.pricing.api.dto.PutPriceRuleRequest;
import zelisline.ub.pricing.api.dto.SellPriceSuggestionResponse;
import zelisline.ub.pricing.api.dto.SellingPriceResponse;
import zelisline.ub.pricing.api.dto.TaxRateResponse;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/pricing")
@RequiredArgsConstructor
public class PricingController {

    private final PricingService pricingService;

    @PostMapping("/selling-prices")
    @PreAuthorize("hasPermission(null, 'pricing.sell_price.set')")
    @ResponseStatus(HttpStatus.CREATED)
    public SellingPriceResponse setSellingPrice(
            @Valid @RequestBody PostSellingPriceRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return pricingService.setSellingPrice(
                TenantRequestIds.resolveBusinessId(request),
                body,
                user.userId()
        );
    }

    @PostMapping("/buying-prices")
    @PreAuthorize("hasPermission(null, 'pricing.cost_price.set')")
    @ResponseStatus(HttpStatus.CREATED)
    public BuyingPriceResponse setBuyingPrice(
            @Valid @RequestBody PostBuyingPriceRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return pricingService.setBuyingPrice(
                TenantRequestIds.resolveBusinessId(request),
                body,
                user.userId()
        );
    }

    @GetMapping("/suggest/sell")
    @PreAuthorize("hasPermission(null, 'pricing.read')")
    public SellPriceSuggestionResponse suggestSell(
            @RequestParam String itemId,
            @RequestParam(required = false) String supplierId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return pricingService.suggestSellPrice(
                TenantRequestIds.resolveBusinessId(request),
                itemId,
                supplierId
        );
    }

    @GetMapping("/price-rules")
    @PreAuthorize("hasPermission(null, 'pricing.read')")
    public List<PriceRuleResponse> listRules(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return pricingService.listPriceRules(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping("/price-rules")
    @PreAuthorize("hasPermission(null, 'pricing.rules.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public PriceRuleResponse createRule(
            @Valid @RequestBody PostPriceRuleRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return pricingService.createPriceRule(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PutMapping("/price-rules/{ruleId}")
    @PreAuthorize("hasPermission(null, 'pricing.rules.manage')")
    public PriceRuleResponse updateRule(
            @PathVariable String ruleId,
            @Valid @RequestBody PutPriceRuleRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return pricingService.updatePriceRule(
                TenantRequestIds.resolveBusinessId(request),
                ruleId,
                body
        );
    }

    @GetMapping("/tax-rates")
    @PreAuthorize("hasPermission(null, 'pricing.read')")
    public List<TaxRateResponse> listTaxRates(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return pricingService.listTaxRates(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping("/tax-rates")
    @PreAuthorize("hasPermission(null, 'pricing.rules.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public TaxRateResponse createTaxRate(
            @Valid @RequestBody PostTaxRateRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return pricingService.createTaxRate(TenantRequestIds.resolveBusinessId(request), body);
    }
}
