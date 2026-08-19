package zelisline.ub.discounts.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.discounts.api.dto.CreateDiscountRequest;
import zelisline.ub.discounts.api.dto.DiscountPreviewResponse;
import zelisline.ub.discounts.api.dto.DiscountResponse;
import zelisline.ub.discounts.api.dto.PreviewDiscountRequest;
import zelisline.ub.discounts.api.dto.ResolvedDiscountRef;
import zelisline.ub.discounts.api.dto.ResolvedPriceResponse;
import zelisline.ub.discounts.api.dto.UpdateDiscountRequest;
import zelisline.ub.discounts.application.DiscountResolutionService;
import zelisline.ub.discounts.application.DiscountService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/discounts")
@RequiredArgsConstructor
@Validated
public class DiscountsController {

    private final DiscountService discountService;
    private final DiscountResolutionService discountResolutionService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'pricing.discounts.manage')")
    public List<DiscountResponse> list(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return discountService.list(TenantRequestIds.resolveBusinessId(request));
    }

    @GetMapping("/{discountId}")
    @PreAuthorize("hasPermission(null, 'pricing.discounts.manage')")
    public DiscountResponse get(@PathVariable String discountId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return discountService.get(TenantRequestIds.resolveBusinessId(request), discountId.trim());
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'pricing.discounts.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public DiscountResponse create(
            @Valid @RequestBody CreateDiscountRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return discountService.create(
                TenantRequestIds.resolveBusinessId(request),
                principal.userId(),
                body);
    }

    @PatchMapping("/{discountId}")
    @PreAuthorize("hasPermission(null, 'pricing.discounts.manage')")
    public DiscountResponse update(
            @PathVariable String discountId,
            @Valid @RequestBody UpdateDiscountRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return discountService.update(
                TenantRequestIds.resolveBusinessId(request),
                discountId.trim(),
                principal.userId(),
                body);
    }

    @PostMapping("/{discountId}/publish")
    @PreAuthorize("hasPermission(null, 'pricing.discounts.manage')")
    public DiscountResponse publish(@PathVariable String discountId, HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return discountService.publish(
                TenantRequestIds.resolveBusinessId(request),
                discountId.trim(),
                principal.userId());
    }

    @PostMapping("/{discountId}/pause")
    @PreAuthorize("hasPermission(null, 'pricing.discounts.manage')")
    public DiscountResponse pause(@PathVariable String discountId, HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return discountService.pause(
                TenantRequestIds.resolveBusinessId(request),
                discountId.trim(),
                principal.userId());
    }

    @PostMapping("/{discountId}/resume")
    @PreAuthorize("hasPermission(null, 'pricing.discounts.manage')")
    public DiscountResponse resume(@PathVariable String discountId, HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return discountService.resume(
                TenantRequestIds.resolveBusinessId(request),
                discountId.trim(),
                principal.userId());
    }

    @PostMapping("/{discountId}/duplicate")
    @PreAuthorize("hasPermission(null, 'pricing.discounts.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public DiscountResponse duplicate(@PathVariable String discountId, HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return discountService.duplicate(
                TenantRequestIds.resolveBusinessId(request),
                discountId.trim(),
                principal.userId());
    }

    @DeleteMapping("/{discountId}")
    @PreAuthorize("hasPermission(null, 'pricing.discounts.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String discountId, HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        discountService.deleteDraft(
                TenantRequestIds.resolveBusinessId(request),
                discountId.trim(),
                principal.userId());
    }

    @PostMapping("/preview")
    @PreAuthorize("hasPermission(null, 'pricing.discounts.manage')")
    public DiscountPreviewResponse preview(
            @Valid @RequestBody PreviewDiscountRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return discountService.preview(TenantRequestIds.resolveBusinessId(request), body);
    }
}
