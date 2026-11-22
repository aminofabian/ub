package zelisline.ub.wholesale.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.wholesale.api.dto.DecideTradeAccountRequest;
import zelisline.ub.wholesale.api.dto.TradeAccountPriceDto;
import zelisline.ub.wholesale.api.dto.TradeAccountResponse;
import zelisline.ub.wholesale.application.TradeAccountService;

/** The signed-in shop's buyers' accounts. Granting credit needs the customer-credit permission. */
@RestController
@RequestMapping("/api/v1/trade-accounts")
@RequiredArgsConstructor
public class TradeAccountsController {

    private final TradeAccountService tradeAccountService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public List<TradeAccountResponse> list(
            @RequestParam(required = false) String status,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return tradeAccountService.list(TenantRequestIds.resolveBusinessId(request), status);
    }

    @PostMapping("/{accountId}/decision")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public TradeAccountResponse decide(
            @PathVariable String accountId,
            @Valid @RequestBody DecideTradeAccountRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return tradeAccountService.decide(TenantRequestIds.resolveBusinessId(request), accountId, body);
    }

    /** The prices this buyer has for particular products. */
    @GetMapping("/{accountId}/prices")
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public List<TradeAccountPriceDto> prices(@PathVariable String accountId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return tradeAccountService.prices(TenantRequestIds.resolveBusinessId(request), accountId);
    }

    /** Replaces the buyer's price list with what is sent. It sets prices, so it needs that permission. */
    @PutMapping("/{accountId}/prices")
    @PreAuthorize("hasPermission(null, 'pricing.sell_price.set')")
    public List<TradeAccountPriceDto> replacePrices(
            @PathVariable String accountId,
            @Valid @RequestBody @jakarta.validation.constraints.Size(max = 500) List<@Valid TradeAccountPriceDto> body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return tradeAccountService.replacePrices(TenantRequestIds.resolveBusinessId(request), accountId, body);
    }
}
