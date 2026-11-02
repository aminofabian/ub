package zelisline.ub.platform.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
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
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.integrations.whatsapp.application.MetaWhatsAppAppSecretVerifier;
import zelisline.ub.integrations.whatsapp.application.MetaWhatsAppNumberResolver;
import zelisline.ub.integrations.whatsapp.application.MetaWhatsAppWabaProvisioner;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.infrastructure.PingAfricaApiClient;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.platform.api.dto.PingAfricaCreditsResponse;
import zelisline.ub.platform.api.dto.PlatformIntegrationsResponse;
import zelisline.ub.platform.api.dto.PlatformWhatsAppAppSecretDtos.AddAppSecretRequest;
import zelisline.ub.platform.api.dto.PlatformWhatsAppAppSecretDtos.AppSecretRow;
import zelisline.ub.platform.api.dto.TestPingAfricaSmsRequest;
import zelisline.ub.platform.api.dto.TestPingAfricaSmsResponse;
import zelisline.ub.platform.api.dto.UpdatePlatformIntegrationsRequest;
import zelisline.ub.platform.api.dto.VerifyMetaWhatsAppAppSecretRequest;
import zelisline.ub.platform.api.dto.VerifyMetaWhatsAppAppSecretResponse;
import zelisline.ub.platform.application.PlatformIntegrationSettingsService;
import zelisline.ub.platform.application.PlatformWhatsAppAppSecretService;
import zelisline.ub.platform.application.ResolvedPingAfricaSmsConfig;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin/platform/integrations")
@RequiredArgsConstructor
public class SuperAdminPlatformIntegrationsController {

    private final PlatformIntegrationSettingsService platformIntegrationSettingsService;
    private final MetaWhatsAppAppSecretVerifier metaWhatsAppAppSecretVerifier;
    private final PlatformWhatsAppAppSecretService platformWhatsAppAppSecretService;
    private final MetaWhatsAppNumberResolver metaWhatsAppNumberResolver;
    private final MetaWhatsAppWabaProvisioner metaWhatsAppWabaProvisioner;
    private final PingAfricaApiClient pingAfricaApiClient;
    private final CustomerMessageDispatcher customerMessageDispatcher;

    @GetMapping
    public PlatformIntegrationsResponse get() {
        return platformIntegrationSettingsService.getForSuperAdmin();
    }

    @PutMapping
    public PlatformIntegrationsResponse update(@Valid @RequestBody UpdatePlatformIntegrationsRequest body) {
        return platformIntegrationSettingsService.update(body);
    }

    /**
     * Live Ping Africa organization credit balance ({@code GET /credits/balance}).
     * Uses the saved platform token — save Integrations first if you just pasted a new key.
     */
    @GetMapping("/ping-africa/credits")
    public PingAfricaCreditsResponse pingAfricaCredits() {
        ResolvedPingAfricaSmsConfig ping = platformIntegrationSettingsService.resolvePingAfricaSms();
        String apiKey = trimToNull(ping.apiKey());
        if (apiKey == null) {
            return new PingAfricaCreditsResponse(
                    false, null, "Ping Africa API token is not configured", 0, false);
        }
        PingAfricaApiClient.CreditsResult result =
                pingAfricaApiClient.fetchCredits(apiKey, ping.baseUrl());
        return new PingAfricaCreditsResponse(
                result.ok(), result.balance(), result.detail(), result.httpStatus(), true);
    }

    /**
     * One-off SMS via the saved Ping Africa credentials (ignores the selected default
     * provider so you can verify Ping Africa before switching tenants over).
     */
    @PostMapping("/ping-africa/test-sms")
    public TestPingAfricaSmsResponse testPingAfricaSms(@Valid @RequestBody TestPingAfricaSmsRequest body) {
        ResolvedPingAfricaSmsConfig ping = platformIntegrationSettingsService.resolvePingAfricaSms();
        String apiKey = trimToNull(ping.apiKey());
        if (apiKey == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ping Africa API token is not configured — paste it under SMS fallback and save first");
        }
        String digits = StkPhoneNormalizer.normalize(body.phone());
        if (digits == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enter a valid Kenyan mobile number (e.g. 07XX XXX XXX or +254…)");
        }
        TenantMessagingConfig messaging = pingAfricaOnlyConfig(ping);
        String message = "Kiosk Ping Africa test SMS — credentials are working.";
        CustomerMessageDispatcher.DeliveryResult delivery =
                customerMessageDispatcher.deliverSmsOnly(messaging, digits, message);
        if ("failed".equals(delivery.outcome()) || "skipped".equals(delivery.outcome())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    delivery.detail() != null && !delivery.detail().isBlank()
                            ? delivery.detail()
                            : "Test SMS could not be delivered");
        }
        Double balanceAfter = null;
        PingAfricaApiClient.CreditsResult credits =
                pingAfricaApiClient.fetchCredits(apiKey, ping.baseUrl());
        if (credits.ok()) {
            balanceAfter = credits.balance();
        }
        return new TestPingAfricaSmsResponse(
                delivery.channel(),
                delivery.outcome(),
                delivery.detail(),
                maskPhone(digits),
                balanceAfter);
    }

    /** Platform-scoped Ping Africa config (no tenant SMS credit metering). */
    private static TenantMessagingConfig pingAfricaOnlyConfig(ResolvedPingAfricaSmsConfig ping) {
        return new TenantMessagingConfig(
                true,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                "ping_africa",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                trimToNull(ping.apiKey()),
                ping.baseUrl(),
                trimToNull(ping.senderId()),
                true,
                null,
                null,
                null);
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() <= 6) {
            return "***";
        }
        return phone.substring(0, 4) + "…" + phone.substring(phone.length() - 2);
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

    public record ProvisionResponse(boolean ok, String wabaId, List<String> steps) {
    }

    /**
     * Enables the platform app on the WABA without waiting for a tenant to add a number: subscribes
     * the app to the WABA (so webhooks arrive) and registers the platform number when a registration
     * PIN is configured. Idempotent — safe to re-run.
     */
    @PostMapping("/meta-whatsapp/provision")
    public ProvisionResponse provisionMetaWhatsApp() {
        String wabaId = metaWhatsAppNumberResolver.resolvePlatformWabaId();
        if (wabaId == null) {
            return new ProvisionResponse(false, null,
                    List.of("Platform WhatsApp isn't configured (access token + phone number ID)."));
        }
        List<String> steps = new ArrayList<>();
        MetaWhatsAppWabaProvisioner.Outcome subscribed = metaWhatsAppWabaProvisioner.ensureSubscribed(wabaId);
        steps.add((subscribed.ok() ? "ok — " : "failed — ") + subscribed.detail());
        String platformPhoneNumberId =
                trimToNull(platformIntegrationSettingsService.resolveMetaWhatsApp().phoneNumberId());
        if (platformPhoneNumberId != null) {
            MetaWhatsAppWabaProvisioner.Outcome registered =
                    metaWhatsAppWabaProvisioner.ensureRegistered(platformPhoneNumberId);
            steps.add((registered.ok() ? "ok — " : "failed — ") + registered.detail());
        }
        return new ProvisionResponse(subscribed.ok(), wabaId, steps);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
