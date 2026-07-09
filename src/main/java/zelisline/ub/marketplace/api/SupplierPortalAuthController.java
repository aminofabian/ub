package zelisline.ub.marketplace.api;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalLoginRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalLoginResponse;
import zelisline.ub.marketplace.application.SupplierPortalAuthService;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/auth")
@RequiredArgsConstructor
public class SupplierPortalAuthController {

    private final SupplierPortalAuthService supplierPortalAuthService;

    @PostMapping("/login")
    public SupplierPortalLoginResponse login(@Valid @RequestBody SupplierPortalLoginRequest request) {
        return supplierPortalAuthService.login(request);
    }
}
