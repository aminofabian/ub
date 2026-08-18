package zelisline.ub.airtime.api;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.airtime.api.dto.AirtimeAvailabilityResponse;
import zelisline.ub.airtime.api.dto.AirtimeOrderResponse;
import zelisline.ub.airtime.api.dto.AirtimeQuoteResponse;
import zelisline.ub.airtime.api.dto.AirtimeSettingsResponse;
import zelisline.ub.airtime.api.dto.AirtimeStorefrontSummaryResponse;
import zelisline.ub.airtime.api.dto.SellAirtimeRequest;
import zelisline.ub.airtime.api.dto.UpdateAirtimeSettingsRequest;
import zelisline.ub.airtime.application.AirtimeSaleService;
import zelisline.ub.airtime.application.BusinessAirtimeSettingsService;
import zelisline.ub.airtime.application.PosAirtimeCollectService;
import zelisline.ub.airtime.domain.AirtimeTenders;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/airtime")
@RequiredArgsConstructor
public class AirtimeTenantController {

    private final AirtimeSaleService saleService;
    private final BusinessAirtimeSettingsService settingsService;
    private final PosAirtimeCollectService posAirtimeCollectService;

    /** Cashier / storefront gate: should airtime be offered, and within what bounds. */
    @GetMapping("/availability")
    @PreAuthorize("hasPermission(null, 'airtime.read')")
    public AirtimeAvailabilityResponse availability(
            HttpServletRequest request,
            @RequestParam(defaultValue = "false") boolean storefront
    ) {
        CurrentTenantUser.require(request);
        return settingsService.availability(TenantRequestIds.resolveBusinessId(request), storefront);
    }

    /** Price a sale before committing — the till shows cost and margin from this. */
    @GetMapping("/quote")
    @PreAuthorize("hasPermission(null, 'airtime.read')")
    public AirtimeQuoteResponse quote(
            HttpServletRequest request,
            @RequestParam String phoneNumber,
            @RequestParam BigDecimal amount
    ) {
        CurrentTenantUser.require(request);
        return saleService.quote(
                TenantRequestIds.resolveBusinessId(request), phoneNumber, amount, false);
    }

    @PostMapping("/orders")
    @PreAuthorize("hasPermission(null, 'airtime.sell')")
    @ResponseStatus(HttpStatus.CREATED)
    public AirtimeOrderResponse sell(
            HttpServletRequest request,
            @Valid @RequestBody SellAirtimeRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required");
        }
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String tender = AirtimeTenders.normalize(body.tender());
        if (AirtimeTenders.MPESA.equals(tender)) {
            return posAirtimeCollectService.prompt(
                    businessId,
                    principal.branchId(),
                    principal.userId(),
                    body.phoneNumber(),
                    body.amount(),
                    body.payerPhone(),
                    body.customerId(),
                    idempotencyKey.trim());
        }
        return saleService.sell(
                businessId,
                principal.branchId(),
                principal.userId(),
                body.phoneNumber(),
                body.amount(),
                body.channel(),
                body.customerId(),
                body.saleId(),
                tender,
                idempotencyKey.trim());
    }

    @GetMapping("/orders")
    @PreAuthorize("hasPermission(null, 'airtime.read')")
    public List<AirtimeOrderResponse> orders(
            HttpServletRequest request,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String channel
    ) {
        CurrentTenantUser.require(request);
        return saleService.list(TenantRequestIds.resolveBusinessId(request), limit, channel);
    }

    @GetMapping("/storefront/summary")
    @PreAuthorize("hasPermission(null, 'airtime.read')")
    public AirtimeStorefrontSummaryResponse storefrontSummary(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return saleService.storefrontSummary(TenantRequestIds.resolveBusinessId(request));
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("hasPermission(null, 'airtime.read')")
    public AirtimeOrderResponse order(HttpServletRequest request, @PathVariable String orderId) {
        CurrentTenantUser.require(request);
        return saleService.get(TenantRequestIds.resolveBusinessId(request), orderId);
    }

    @GetMapping("/settings")
    @PreAuthorize("hasPermission(null, 'airtime.read')")
    public AirtimeSettingsResponse settings(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return settingsService.getSettings(TenantRequestIds.resolveBusinessId(request));
    }

    @PatchMapping("/settings")
    @PreAuthorize("hasPermission(null, 'airtime.manage')")
    public AirtimeSettingsResponse updateSettings(
            HttpServletRequest request,
            @Valid @RequestBody UpdateAirtimeSettingsRequest body
    ) {
        CurrentTenantUser.require(request);
        return settingsService.updateSettings(TenantRequestIds.resolveBusinessId(request), body);
    }
}
