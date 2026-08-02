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
import zelisline.ub.platform.pageseal.api.dto.PageSealOkResponse;
import zelisline.ub.platform.pageseal.api.dto.PageSealSendCodeResponse;
import zelisline.ub.platform.pageseal.api.dto.PageSealStatusResponse;
import zelisline.ub.platform.pageseal.api.dto.PageSealUnlockRequest;
import zelisline.ub.platform.pageseal.api.dto.PageSealUnlockResponse;
import zelisline.ub.platform.pageseal.api.dto.PageSealVerifySetRequest;
import zelisline.ub.platform.pageseal.application.PageSealService;
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
    private final PageSealService pageSealService;

    @GetMapping("/{slug}")
    public PublicSupplierPortalResponse overview(
            @PathVariable String slug,
            @RequestHeader(value = PageSealController.UNLOCK_HEADER, required = false) String unlockToken,
            HttpServletRequest request
    ) {
        return publicSupplierPortalService.overview(
                publicHostBusinessResolver.resolveOrThrow(request), slug, unlockToken);
    }

    /** Page-seal status for this shop supplier portal (preferred path). */
    @GetMapping("/{slug}/page-seal")
    public PageSealStatusResponse pageSealStatus(
            @PathVariable String slug,
            @RequestHeader(value = PageSealController.UNLOCK_HEADER, required = false) String unlockToken,
            HttpServletRequest request
    ) {
        return pageSealService.shopSupplierStatus(
                publicHostBusinessResolver.resolveOrThrow(request), slug, unlockToken);
    }

    @PostMapping("/{slug}/page-seal/send-code")
    public PageSealSendCodeResponse pageSealSendCode(
            @PathVariable String slug,
            HttpServletRequest request
    ) {
        return pageSealService.sendShopSupplierSealCode(
                publicHostBusinessResolver.resolveOrThrow(request), slug);
    }

    @PostMapping("/{slug}/page-seal/seal")
    public PageSealOkResponse pageSealSeal(
            @PathVariable String slug,
            @Valid @RequestBody PageSealVerifySetRequest body,
            HttpServletRequest request
    ) {
        return pageSealService.verifyAndSealShopSupplier(
                publicHostBusinessResolver.resolveOrThrow(request),
                slug,
                body.code(),
                body.pin(),
                body.confirmPin());
    }

    @PostMapping("/{slug}/page-seal/unseal")
    public PageSealOkResponse pageSealUnseal(
            @PathVariable String slug,
            @Valid @RequestBody PageSealUnlockRequest body,
            HttpServletRequest request
    ) {
        return pageSealService.unsealShopSupplier(
                publicHostBusinessResolver.resolveOrThrow(request), slug, body.pin());
    }

    @PostMapping("/{slug}/page-seal/unlock")
    public PageSealUnlockResponse pageSealUnlock(
            @PathVariable String slug,
            @Valid @RequestBody PageSealUnlockRequest body,
            HttpServletRequest request
    ) {
        return pageSealService.unlockShopSupplier(
                publicHostBusinessResolver.resolveOrThrow(request), slug, body.pin());
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
