package zelisline.ub.suppliers.api;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.pageseal.api.PageSealController;
import zelisline.ub.suppliers.api.dto.PublicSupplierComplaintRequest;
import zelisline.ub.suppliers.api.dto.PublicSupplierComplaintResponse;
import zelisline.ub.suppliers.api.dto.PublicSupplierPortalResponse;
import zelisline.ub.suppliers.application.PublicSupplierPortalService;
import zelisline.ub.tenancy.application.PublicHostBusinessResolver;

/**
 * Public supplier reference portal — amount owed, supply history, movements, complaints.
 * Tenant from Host / {@code X-Tenant-Host}. Path key is the supplier name slug.
 */
@Validated
@RestController
@RequestMapping("/api/v1/public/suppliers")
@RequiredArgsConstructor
public class PublicSupplierPortalController {

    private final PublicSupplierPortalService publicSupplierPortalService;
    private final PublicHostBusinessResolver publicHostBusinessResolver;

    @GetMapping("/{slug}")
    public PublicSupplierPortalResponse overview(
            @PathVariable String slug,
            @RequestHeader(value = PageSealController.UNLOCK_HEADER, required = false) String unlockToken,
            HttpServletRequest request
    ) {
        return publicSupplierPortalService.overview(
                publicHostBusinessResolver.resolveOrThrow(request), slug, unlockToken);
    }

    @PostMapping("/{slug}/complaints")
    @ResponseStatus(HttpStatus.CREATED)
    public PublicSupplierComplaintResponse complaint(
            @PathVariable String slug,
            @Valid @RequestBody PublicSupplierComplaintRequest body,
            HttpServletRequest request
    ) {
        return publicSupplierPortalService.submitComplaint(
                publicHostBusinessResolver.resolveOrThrow(request),
                slug,
                body,
                request);
    }
}
