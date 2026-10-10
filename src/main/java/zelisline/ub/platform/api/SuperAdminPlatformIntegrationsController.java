package zelisline.ub.platform.api;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.integrations.whatsapp.application.MetaWhatsAppAppSecretVerifier;
import zelisline.ub.platform.api.dto.PlatformIntegrationsResponse;
import zelisline.ub.platform.api.dto.UpdatePlatformIntegrationsRequest;
import zelisline.ub.platform.api.dto.VerifyMetaWhatsAppAppSecretRequest;
import zelisline.ub.platform.api.dto.VerifyMetaWhatsAppAppSecretResponse;
import zelisline.ub.platform.application.PlatformIntegrationSettingsService;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin/platform/integrations")
@RequiredArgsConstructor
public class SuperAdminPlatformIntegrationsController {

    private final PlatformIntegrationSettingsService platformIntegrationSettingsService;
    private final MetaWhatsAppAppSecretVerifier metaWhatsAppAppSecretVerifier;

    @GetMapping
    public PlatformIntegrationsResponse get() {
        return platformIntegrationSettingsService.getForSuperAdmin();
    }

    @PutMapping
    public PlatformIntegrationsResponse update(@Valid @RequestBody UpdatePlatformIntegrationsRequest body) {
        return platformIntegrationSettingsService.update(body);
    }

    /**
     * Validates the Meta app secret against the access token via an {@code appsecret_proof} call,
     * so a wrong secret is caught here rather than as a stream of 403s on
     * {@code /webhooks/whatsapp}. Pass {@code candidateAppSecret} to test a value before saving.
     */
    @PostMapping("/meta-whatsapp/verify-app-secret")
    public VerifyMetaWhatsAppAppSecretResponse verifyMetaAppSecret(
            @RequestBody(required = false) VerifyMetaWhatsAppAppSecretRequest body) {
        MetaWhatsAppAppSecretVerifier.Result result = metaWhatsAppAppSecretVerifier.verify(
                platformIntegrationSettingsService.resolveMetaWhatsApp(),
                body == null ? null : body.candidateAppSecret());
        return new VerifyMetaWhatsAppAppSecretResponse(
                result.verified(),
                result.status().name(),
                result.usedCandidate(),
                result.configuredSecretPresent(),
                result.detail());
    }
}
