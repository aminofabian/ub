package zelisline.ub.integrations.pickupmtaani.api;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniCredentialRequest;
import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniCredentialResponse;
import zelisline.ub.integrations.pickupmtaani.application.PickupMtaaniCredentialService;

/**
 * Super-admin management of a tenant's Pickup Mtaani credential (scope §6).
 * The key is write-only; a {@code PUT} replaces it and re-verifies upstream.
 * Reached from the super-admin business detail, authorized by
 * {@code /api/v1/super-admin/**}.
 */
@Validated
@RestController
@RequestMapping("/api/v1/super-admin/businesses/{businessId}/integrations/pickup-mtaani")
@RequiredArgsConstructor
public class SuperAdminPickupMtaaniController {

    private final PickupMtaaniCredentialService credentialService;

    @GetMapping
    public PickupMtaaniCredentialResponse get(@PathVariable String businessId) {
        return credentialService.read(businessId);
    }

    @PutMapping
    public PickupMtaaniCredentialResponse save(
            @PathVariable String businessId,
            @Valid @RequestBody PickupMtaaniCredentialRequest body
    ) {
        return credentialService.save(businessId, body.apiKey());
    }

    @DeleteMapping
    public PickupMtaaniCredentialResponse disconnect(@PathVariable String businessId) {
        return credentialService.disconnect(businessId);
    }
}
