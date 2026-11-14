package zelisline.ub.serviceops.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.serviceops.api.dto.PackageDtos.CustomerPackageResponse;
import zelisline.ub.serviceops.api.dto.PackageDtos.PackageOfferResponse;
import zelisline.ub.serviceops.api.dto.PackageDtos.SellPackageRequest;
import zelisline.ub.serviceops.api.dto.PackageDtos.SetPackageOfferRequest;
import zelisline.ub.serviceops.application.ServicePackageService;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;
import zelisline.ub.till.application.TillDeviceService;

/** Prepaid packages of a service (ADR 0015). */
@Validated
@RestController
@RequestMapping("/api/v1/service-packages")
@RequiredArgsConstructor
public class ServicePackagesController {

    private final ServicePackageService packageService;
    private final BranchResolutionService branchResolutionService;

    @GetMapping("/offers")
    @PreAuthorize("hasPermission(null, 'sales.sell') or hasPermission(null, 'services.catalog.manage')")
    public List<PackageOfferResponse> offers(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return packageService.offers(TenantRequestIds.resolveBusinessId(request));
    }

    /** Sets the package for a service. A body with no sessions removes the offer. */
    @PutMapping("/offers/{serviceId}")
    @PreAuthorize("hasPermission(null, 'services.catalog.manage')")
    public PackageOfferResponse setOffer(
            @PathVariable String serviceId, @Valid @RequestBody SetPackageOfferRequest body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return packageService.setOffer(TenantRequestIds.resolveBusinessId(request), serviceId, body);
    }

    @PostMapping("/sell")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public CustomerPackageResponse sell(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SellPackageRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        String branch = branchResolutionService.requireBranchForLockedRole(
                caller.roleId(), caller.branchId(), body.branchId());
        SellPackageRequest safe = new SellPackageRequest(
                branch, body.customerId(), body.serviceId(), body.method(), body.reference());
        return packageService.sell(TenantRequestIds.resolveBusinessId(request), safe, idempotencyKey,
                caller.userId(), request.getHeader(TillDeviceService.TILL_DEVICE_HEADER));
    }

    /** A customer's packages, newest first, used-up and expired ones included. */
    @GetMapping
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public List<CustomerPackageResponse> forCustomer(@RequestParam String customerId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return packageService.forCustomer(TenantRequestIds.resolveBusinessId(request), customerId);
    }
}
