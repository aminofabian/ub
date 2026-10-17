package zelisline.ub.integrations.easytax.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.integrations.easytax.api.dto.EasyTaxSettingsRequest;
import zelisline.ub.integrations.easytax.domain.EasyTaxEnvironment;
import zelisline.ub.integrations.easytax.domain.EasyTaxSettings;
import zelisline.ub.integrations.easytax.infrastructure.EasyTaxClient;
import zelisline.ub.integrations.easytax.repository.EasyTaxSettingsRepository;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;

@ExtendWith(MockitoExtension.class)
class EasyTaxSettingsServiceTest {

    private static final String BASE_URL = "http://host:8888/api/v1";

    @Mock
    private EasyTaxSettingsRepository repository;
    @Mock
    private PlatformEasyTaxSettingsService platformSettingsService;
    @Mock
    private EasyTaxClient client;
    @Mock
    private CredentialEncryptionService encryptionService;

    private EasyTaxSettingsService service;

    @BeforeEach
    void setUp() {
        service = new EasyTaxSettingsService(
                repository, platformSettingsService, client, encryptionService);
    }

    @Test
    void defaultsToDisabledWhenNoRowExists() {
        when(repository.findById("biz-1")).thenReturn(Optional.empty());
        when(platformSettingsService.resolveBaseUrl(EasyTaxEnvironment.SANDBOX)).thenReturn(Optional.empty());

        var response = service.getSettings("biz-1");

        assertThat(response.enabled()).isFalse();
        assertThat(response.environment()).isEqualTo("SANDBOX");
        assertThat(response.configured()).isFalse();
        assertThat(response.hasPassword()).isFalse();
        // The platform owns availability and the endpoint.
        assertThat(response.platformEnabled()).isFalse();
        assertThat(response.resolvedBaseUrl()).isNull();
    }

    @Test
    void rejectsEnablingWhenTenantHasNotSuppliedItsFields() {
        when(repository.findById("biz-1"))
                .thenReturn(Optional.of(EasyTaxSettings.disabledFor("biz-1")));

        assertThatThrownBy(() -> service.updateSettings(
                        "biz-1",
                        new EasyTaxSettingsRequest(true, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("before enabling");
    }

    @Test
    void rejectsEnablingWhenThePlatformHasNotEnabledIt() {
        EasyTaxSettings existing = fullyConfiguredEntity("biz-1");
        existing.setEnabled(false);
        when(repository.findById("biz-1")).thenReturn(Optional.of(existing));
        when(platformSettingsService.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.updateSettings(
                        "biz-1",
                        new EasyTaxSettingsRequest(true, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("platform administrator");
    }

    @Test
    void storesPasswordEncryptedAndDoesNotEchoIt() {
        when(repository.findById("biz-1"))
                .thenReturn(Optional.of(EasyTaxSettings.disabledFor("biz-1")));
        when(encryptionService.encryptSecret("s3cret")).thenReturn("enc-blob");
        when(platformSettingsService.isEnabled()).thenReturn(true);
        when(platformSettingsService.resolveBaseUrl(EasyTaxEnvironment.SANDBOX))
                .thenReturn(Optional.of(BASE_URL));
        when(repository.save(any(EasyTaxSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.updateSettings("biz-1", fullyConfigured(true, "s3cret"));

        ArgumentCaptor<EasyTaxSettings> cap = ArgumentCaptor.forClass(EasyTaxSettings.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getPasswordEncrypted()).isEqualTo("enc-blob");
        assertThat(response.hasPassword()).isTrue();
        assertThat(response.enabled()).isTrue();
        assertThat(response.configured()).isTrue();
        assertThat(response.platformEnabled()).isTrue();
        assertThat(response.resolvedBaseUrl()).isEqualTo(BASE_URL);
    }

    @Test
    void keepsStoredPasswordWhenBlankSupplied() {
        EasyTaxSettings existing = EasyTaxSettings.disabledFor("biz-1");
        existing.setPasswordEncrypted("enc-blob");
        when(repository.findById("biz-1")).thenReturn(Optional.of(existing));
        when(platformSettingsService.resolveBaseUrl(EasyTaxEnvironment.SANDBOX)).thenReturn(Optional.empty());
        when(repository.save(any(EasyTaxSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.updateSettings("biz-1", fullyConfigured(false, ""));

        verify(encryptionService, never()).encryptSecret(any());
        assertThat(existing.getPasswordEncrypted()).isEqualTo("enc-blob");
        assertThat(response.hasPassword()).isTrue();
    }

    @Test
    void rejectsUnknownEnvironment() {
        when(repository.findById("biz-1"))
                .thenReturn(Optional.of(EasyTaxSettings.disabledFor("biz-1")));

        assertThatThrownBy(() -> service.updateSettings(
                        "biz-1",
                        new EasyTaxSettingsRequest(null, "staging", null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SANDBOX or PRODUCTION");
    }

    @Test
    void resolveActiveRequiresEnabledAndConfigured() {
        EasyTaxSettings enabledOnly = EasyTaxSettings.disabledFor("biz-1");
        enabledOnly.setEnabled(true);
        when(repository.findById("biz-1")).thenReturn(Optional.of(enabledOnly));
        assertThat(service.resolveActive("biz-1")).isEmpty();

        EasyTaxSettings ready = fullyConfiguredEntity("biz-1");
        when(repository.findById("biz-1")).thenReturn(Optional.of(ready));
        assertThat(service.resolveActive("biz-1")).isPresent();
    }

    @Test
    void testConnectionUsesThePlatformEndpointAndPersistsResult() {
        EasyTaxSettings ready = fullyConfiguredEntity("biz-1");
        when(repository.findById("biz-1")).thenReturn(Optional.of(ready));
        when(platformSettingsService.resolveBaseUrl(EasyTaxEnvironment.SANDBOX))
                .thenReturn(Optional.of(BASE_URL));
        when(encryptionService.decrypt("enc-blob")).thenReturn("s3cret");
        when(client.probe(BASE_URL, "admin", "s3cret"))
                .thenReturn(EasyTaxClient.ProbeResult.ok("Connection succeeded (HTTP 200)"));
        when(repository.save(any(EasyTaxSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.testConnection("biz-1");

        assertThat(result.ok()).isTrue();
        assertThat(ready.getLastTestOk()).isTrue();
        assertThat(ready.getLastTestedAt()).isNotNull();
    }

    @Test
    void testConnectionExplainsWhenThePlatformHasNoEndpoint() {
        EasyTaxSettings ready = fullyConfiguredEntity("biz-1");
        when(repository.findById("biz-1")).thenReturn(Optional.of(ready));
        when(platformSettingsService.resolveBaseUrl(EasyTaxEnvironment.SANDBOX)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.testConnection("biz-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("platform administrator");
    }

    private static EasyTaxSettingsRequest fullyConfigured(boolean enabled, String password) {
        return new EasyTaxSettingsRequest(enabled, "SANDBOX", "admin", password, "P051234567X");
    }

    private static EasyTaxSettings fullyConfiguredEntity(String businessId) {
        EasyTaxSettings settings = EasyTaxSettings.disabledFor(businessId);
        settings.setEnabled(true);
        settings.setEnvironment(EasyTaxEnvironment.SANDBOX);
        settings.setUsername("admin");
        settings.setPasswordEncrypted("enc-blob");
        settings.setTin("P051234567X");
        return settings;
    }
}
