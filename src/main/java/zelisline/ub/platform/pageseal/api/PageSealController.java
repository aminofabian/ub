package zelisline.ub.platform.pageseal.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.pageseal.api.dto.PageSealOkResponse;
import zelisline.ub.platform.pageseal.api.dto.PageSealSendCodeResponse;
import zelisline.ub.platform.pageseal.api.dto.PageSealStatusResponse;
import zelisline.ub.platform.pageseal.api.dto.PageSealUnlockRequest;
import zelisline.ub.platform.pageseal.api.dto.PageSealUnlockResponse;
import zelisline.ub.platform.pageseal.api.dto.PageSealVerifySetRequest;
import zelisline.ub.platform.pageseal.application.PageSealService;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;
import zelisline.ub.tenancy.application.PublicHostBusinessResolver;

@Validated
@RestController
@RequiredArgsConstructor
public class PageSealController {

    public static final String UNLOCK_HEADER = "X-Page-Unlock";

    private final PageSealService pageSealService;
    private final PublicHostBusinessResolver publicHostBusinessResolver;

    // —— Public status + unlock ——

    @GetMapping("/api/v1/public/page-seals/supplier/{username}")
    public PageSealStatusResponse supplierStatus(
            @PathVariable String username,
            @RequestHeader(value = UNLOCK_HEADER, required = false) String unlockToken
    ) {
        return pageSealService.supplierStatus(username, unlockToken);
    }

    @PostMapping("/api/v1/public/page-seals/supplier/{username}/unlock")
    public PageSealUnlockResponse unlockSupplier(
            @PathVariable String username,
            @Valid @RequestBody PageSealUnlockRequest body
    ) {
        return pageSealService.unlockSupplier(username, body.pin());
    }

    @GetMapping("/api/v1/public/page-seals/customer-tab/{phone}")
    public PageSealStatusResponse customerTabStatus(
            @PathVariable String phone,
            @RequestHeader(value = UNLOCK_HEADER, required = false) String unlockToken,
            HttpServletRequest request
    ) {
        return pageSealService.customerTabStatus(
                publicHostBusinessResolver.resolveOrThrow(request), phone, unlockToken);
    }

    @PostMapping("/api/v1/public/page-seals/customer-tab/{phone}/unlock")
    public PageSealUnlockResponse unlockCustomerTab(
            @PathVariable String phone,
            @Valid @RequestBody PageSealUnlockRequest body,
            HttpServletRequest request
    ) {
        return pageSealService.unlockCustomerTab(
                publicHostBusinessResolver.resolveOrThrow(request), phone, body.pin());
    }

    @PostMapping("/api/v1/public/page-seals/customer-tab/{phone}/send-code")
    public PageSealSendCodeResponse sendCustomerTabCode(
            @PathVariable String phone,
            HttpServletRequest request
    ) {
        return pageSealService.sendCustomerTabSealCode(
                publicHostBusinessResolver.resolveOrThrow(request), phone);
    }

    @PostMapping("/api/v1/public/page-seals/customer-tab/{phone}/seal")
    public PageSealOkResponse sealCustomerTab(
            @PathVariable String phone,
            @Valid @RequestBody PageSealVerifySetRequest body,
            HttpServletRequest request
    ) {
        return pageSealService.verifyAndSealCustomerTab(
                publicHostBusinessResolver.resolveOrThrow(request),
                phone,
                body.code(),
                body.pin(),
                body.confirmPin());
    }

    @PostMapping("/api/v1/public/page-seals/customer-tab/{phone}/unseal")
    public PageSealOkResponse unsealCustomerTab(
            @PathVariable String phone,
            @Valid @RequestBody PageSealUnlockRequest body,
            HttpServletRequest request
    ) {
        return pageSealService.unsealCustomerTab(
                publicHostBusinessResolver.resolveOrThrow(request), phone, body.pin());
    }

    // —— Authenticated supplier seal management ——

    @PostMapping("/api/v1/supplier-portal/page-seal/send-code")
    @PreAuthorize("hasRole('SUPPLIER')")
    public PageSealSendCodeResponse sendSupplierCode() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return pageSealService.sendSupplierSealCode(principal.marketplaceSupplierId());
    }

    @PostMapping("/api/v1/supplier-portal/page-seal/seal")
    @PreAuthorize("hasRole('SUPPLIER')")
    public PageSealOkResponse sealSupplier(@Valid @RequestBody PageSealVerifySetRequest body) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return pageSealService.verifyAndSealSupplier(
                principal.marketplaceSupplierId(), body.code(), body.pin(), body.confirmPin());
    }

    @PostMapping("/api/v1/supplier-portal/page-seal/unseal")
    @PreAuthorize("hasRole('SUPPLIER')")
    public PageSealOkResponse unsealSupplier(@Valid @RequestBody PageSealUnlockRequest body) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return pageSealService.unsealSupplier(principal.marketplaceSupplierId(), body.pin());
    }

    @GetMapping("/api/v1/supplier-portal/page-seal/status")
    @PreAuthorize("hasRole('SUPPLIER')")
    public PageSealStatusResponse supplierOwnerStatus(
            @RequestHeader(value = UNLOCK_HEADER, required = false) String unlockToken
    ) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        var supplier = pageSealService.supplierStatusById(principal.marketplaceSupplierId(), unlockToken);
        return supplier;
    }
}
