package zelisline.ub.tenancy.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.dto.CreateDomainOrderRequest;
import zelisline.ub.tenancy.api.dto.DomainOrderResponse;
import zelisline.ub.tenancy.api.dto.DomainSearchRequest;
import zelisline.ub.tenancy.api.dto.DomainSearchResponse;
import zelisline.ub.tenancy.api.dto.PayDomainOrderRequest;
import zelisline.ub.tenancy.api.dto.PayDomainOrderResponse;
import zelisline.ub.tenancy.api.dto.RequestDomainHelpRequest;
import zelisline.ub.tenancy.api.dto.RequestDomainHelpResponse;
import zelisline.ub.tenancy.application.DomainHelpRequestService;
import zelisline.ub.tenancy.application.DomainPurchaseService;

@Validated
@RestController
@RequestMapping("/api/v1/businesses/me/domain-orders")
@RequiredArgsConstructor
public class MyDomainOrdersController {

    private static final String REQUIRES_MANAGE_SETTINGS =
            "hasPermission(null, 'business.manage_settings')";

    private final DomainPurchaseService domainPurchaseService;
    private final DomainHelpRequestService domainHelpRequestService;

    @PostMapping("/search")
    @PreAuthorize(REQUIRES_MANAGE_SETTINGS)
    public DomainSearchResponse search(
            HttpServletRequest request,
            @Valid @RequestBody DomainSearchRequest body
    ) {
        return domainPurchaseService.search(
                TenantRequestIds.resolveBusinessId(request),
                body == null ? null : body.query()
        );
    }

    @GetMapping
    @PreAuthorize(REQUIRES_MANAGE_SETTINGS)
    public List<DomainOrderResponse> list(HttpServletRequest request) {
        return domainPurchaseService.listOrders(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping
    @PreAuthorize(REQUIRES_MANAGE_SETTINGS)
    @ResponseStatus(HttpStatus.CREATED)
    public DomainOrderResponse create(
            HttpServletRequest request,
            @Valid @RequestBody CreateDomainOrderRequest body
    ) {
        return domainPurchaseService.createOrder(
                TenantRequestIds.resolveBusinessId(request),
                body
        );
    }

    @GetMapping("/{orderId}")
    @PreAuthorize(REQUIRES_MANAGE_SETTINGS)
    public DomainOrderResponse get(HttpServletRequest request, @PathVariable String orderId) {
        return domainPurchaseService.getOrder(
                TenantRequestIds.resolveBusinessId(request),
                orderId
        );
    }

    @PostMapping("/{orderId}/sync")
    @PreAuthorize(REQUIRES_MANAGE_SETTINGS)
    public DomainOrderResponse sync(HttpServletRequest request, @PathVariable String orderId) {
        return domainPurchaseService.syncOrder(
                TenantRequestIds.resolveBusinessId(request),
                orderId
        );
    }

    @PostMapping("/help")
    @PreAuthorize(REQUIRES_MANAGE_SETTINGS)
    @ResponseStatus(HttpStatus.CREATED)
    public RequestDomainHelpResponse help(
            HttpServletRequest request,
            @Valid @RequestBody RequestDomainHelpRequest body
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return domainHelpRequestService.request(
                TenantRequestIds.resolveBusinessId(request),
                principal.userId(),
                body
        );
    }

    @PostMapping("/{orderId}/pay")
    @PreAuthorize(REQUIRES_MANAGE_SETTINGS)
    public PayDomainOrderResponse pay(
            HttpServletRequest request,
            @PathVariable String orderId,
            @Valid @RequestBody PayDomainOrderRequest body
    ) {
        return domainPurchaseService.initiatePayment(
                TenantRequestIds.resolveBusinessId(request),
                orderId,
                body
        );
    }
}
