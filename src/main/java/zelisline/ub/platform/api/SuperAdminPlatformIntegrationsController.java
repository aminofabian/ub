package zelisline.ub.platform.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.integrations.whatsapp.application.MetaWhatsAppAppSecretVerifier;
import zelisline.ub.platform.api.dto.PlatformIntegrationsResponse;
import zelisline.ub.platform.api.dto.PlatformWhatsAppAppSecretDtos.AddAppSecretRequest;
import zelisline.ub.platform.api.dto.PlatformWhatsAppAppSecretDtos.AppSecretRow;
import zelisline.ub.platform.api.dto.UpdatePlatformIntegrationsRequest;
import zelisline.ub.platform.api.dto.VerifyMetaWhatsAppAppSecretRequest;
import zelisline.ub.platform.api.dto.VerifyMetaWhatsAppAppSecretResponse;
import zelisline.ub.platform.application.PlatformIntegrationSettingsService;
import zelisline.ub.platform.application.PlatformWhatsAppAppSecretService;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin/platform/integrations")
@RequiredArgsConstructor
public class SuperAdminPlatformIntegrationsController {

    private final PlatformIntegrationSettingsService platformIntegrationSettingsService;
    private final MetaWhatsAppAppSecretVerifier metaWhatsAppAppSecretVerifier;
    private final PlatformWhatsAppAppSecretService platformWhatsAppAppSecretService;

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
                result.configuredSecretFingerprint(),
                result.detail(),
                result.wabaId(),
                result.wabaName(),
                result.subscribedApps().stream()
                        .map(app -> new VerifyMetaWhatsAppAppSecretResponse.SubscribedApp(app.id(), app.name()))
                        .toList());
    }

    /**
     * Additional Meta app secrets accepted for inbound webhook verification. Meta signs each
     * delivery with the sending app's secret, so a WABA with several subscribed apps needs each
     * app's secret here. Only last-4 fingerprints are ever returned.
     */
    @GetMapping("/meta-whatsapp/app-secrets")
    public List<AppSecretRow> listMetaAppSecrets() {
        return platformWhatsAppAppSecretService.list();
    }

    @PostMapping("/meta-whatsapp/app-secrets")
    public AppSecretRow addMetaAppSecret(@Valid @RequestBody AddAppSecretRequest body) {
        return platformWhatsAppAppSecretService.add(body.label(), body.appSecret());
    }

    @DeleteMapping("/meta-whatsapp/app-secrets/{id}")
    public ResponseEntity<Void> deleteMetaAppSecret(@PathVariable String id) {
        platformWhatsAppAppSecretService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
