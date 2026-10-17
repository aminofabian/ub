package zelisline.ub.integrations.easytax.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.easytax.api.dto.EasyTaxTestResponse;
import zelisline.ub.integrations.easytax.api.dto.SupplierEasyTaxSettingsRequest;
import zelisline.ub.integrations.easytax.api.dto.SupplierEasyTaxSettingsResponse;
import zelisline.ub.integrations.easytax.application.SupplierEasyTaxSettingsService;
import zelisline.ub.platform.security.CurrentSupplierUser;

/**
 * Supplier portal opt-in for EasyTax / KRA eTIMS. A supplier files under its own TIN,
 * so this reads and writes its own configuration only.
 */
@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/easytax")
@RequiredArgsConstructor
public class SupplierEasyTaxSettingsController {

    private final SupplierEasyTaxSettingsService service;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'supplier.tax.manage')")
    public SupplierEasyTaxSettingsResponse get() {
        return service.get(CurrentSupplierUser.require().marketplaceSupplierId());
    }

    @PutMapping
    @PreAuthorize("hasPermission(null, 'supplier.tax.manage')")
    public SupplierEasyTaxSettingsResponse update(
            @Valid @RequestBody SupplierEasyTaxSettingsRequest body
    ) {
        return service.update(CurrentSupplierUser.require().marketplaceSupplierId(), body);
    }

    @PostMapping("/test")
    @PreAuthorize("hasPermission(null, 'supplier.tax.manage')")
    public EasyTaxTestResponse test() {
        return service.testConnection(CurrentSupplierUser.require().marketplaceSupplierId());
    }
}
