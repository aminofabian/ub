package zelisline.ub.airtime.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.airtime.api.dto.AirtimeOrderResponse;
import zelisline.ub.airtime.api.dto.PlatformAirtimeSettingsResponse;
import zelisline.ub.airtime.api.dto.UpdatePlatformAirtimeSettingsRequest;
import zelisline.ub.airtime.application.AirtimeSaleService;
import zelisline.ub.airtime.application.PlatformAirtimeSettingsService;

/** Platform airtime configuration and cross-tenant ops visibility. */
@RestController
@RequestMapping("/api/v1/super-admin/airtime")
@RequiredArgsConstructor
public class SuperAdminAirtimeController {

    private final PlatformAirtimeSettingsService settingsService;
    private final AirtimeSaleService saleService;

    @GetMapping
    public PlatformAirtimeSettingsResponse get() {
        return settingsService.getForSuperAdmin();
    }

    @PatchMapping
    public PlatformAirtimeSettingsResponse update(
            @Valid @RequestBody UpdatePlatformAirtimeSettingsRequest body
    ) {
        return settingsService.update(body);
    }

    /** Verify the stored Instalipa credentials still authenticate. */
    @PostMapping("/test")
    public PlatformAirtimeSettingsResponse test() {
        return settingsService.testConnection();
    }

    /** Recent airtime across all tenants, with untouched provider failure text. */
    @GetMapping("/orders")
    public List<AirtimeOrderResponse> orders(@RequestParam(defaultValue = "50") int limit) {
        return saleService.listForSuperAdmin(limit);
    }

    /** Force a status re-query when a callback was missed. */
    @PostMapping("/orders/{orderId}/requery")
    public AirtimeOrderResponse requery(@PathVariable String orderId) {
        return saleService.requeryForSuperAdmin(orderId);
    }
}
