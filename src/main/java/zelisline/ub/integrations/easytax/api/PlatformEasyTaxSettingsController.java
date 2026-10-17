package zelisline.ub.integrations.easytax.api;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.easytax.api.dto.PlatformEasyTaxSettingsRequest;
import zelisline.ub.integrations.easytax.api.dto.PlatformEasyTaxSettingsResponse;
import zelisline.ub.integrations.easytax.application.PlatformEasyTaxSettingsService;

/**
 * Super-admin EasyTax configuration. The {@code /super-admin} prefix is gated by the
 * platform security filter, matching the other platform settings endpoints.
 */
@Validated
@RestController
@RequestMapping("/api/v1/super-admin/integrations/easytax")
@RequiredArgsConstructor
public class PlatformEasyTaxSettingsController {

    private final PlatformEasyTaxSettingsService service;

    @GetMapping
    public PlatformEasyTaxSettingsResponse get() {
        return service.get();
    }

    @PutMapping
    public PlatformEasyTaxSettingsResponse update(
            @Valid @RequestBody PlatformEasyTaxSettingsRequest body
    ) {
        return service.update(body);
    }
}
