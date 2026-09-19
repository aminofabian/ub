package zelisline.ub.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.payments.api.dto.PlatformMpesaCustodySettingsResponse;
import zelisline.ub.payments.api.dto.UpdatePlatformMpesaCustodySettingsRequest;
import zelisline.ub.payments.domain.PlatformMpesaCustodyProviders;
import zelisline.ub.payments.domain.PlatformMpesaCustodySettings;
import zelisline.ub.payments.repository.PlatformMpesaCustodySettingsRepository;

/**
 * SA custody-provider gating: only ready rails can be selected; Daraja stays blocked
 * until disburse ships.
 */
@SuppressWarnings("unchecked")
class PlatformMpesaCustodySettingsServiceTest {

    private PlatformMpesaCustodySettingsRepository repository;
    private PlatformKioskPaySettingsService kioskPaySettings;
    private PlatformDarajaSettingsService darajaSettings;
    private ObjectProvider<PlatformKioskPaySettingsService> kioskProvider;
    private ObjectProvider<PlatformDarajaSettingsService> darajaProvider;

    private PlatformMpesaCustodySettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformMpesaCustodySettingsRepository.class);
        kioskPaySettings = mock(PlatformKioskPaySettingsService.class);
        darajaSettings = mock(PlatformDarajaSettingsService.class);
        kioskProvider = mock(ObjectProvider.class);
        darajaProvider = mock(ObjectProvider.class);

        service = new PlatformMpesaCustodySettingsService(repository, kioskProvider, darajaProvider);

        when(kioskProvider.getIfAvailable()).thenReturn(kioskPaySettings);
        when(darajaProvider.getIfAvailable()).thenReturn(darajaSettings);
        when(repository.findById(PlatformMpesaCustodySettings.SINGLETON_ID))
                .thenReturn(Optional.of(settings(PlatformMpesaCustodyProviders.OFF)));
        when(repository.save(any(PlatformMpesaCustodySettings.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void update_offIsAlwaysAllowed() {
        PlatformMpesaCustodySettingsResponse res =
                service.update(new UpdatePlatformMpesaCustodySettingsRequest("OFF"));

        assertThat(res.custodyProvider()).isEqualTo(PlatformMpesaCustodyProviders.OFF);
    }

    @Test
    void update_kopokopoRejectedWhenCredentialsMissing() {
        when(kioskPaySettings.kopokopoCredentials()).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.update(new UpdatePlatformMpesaCustodySettingsRequest("KOPOKOPO")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void update_kopokopoAllowedWhenReady() {
        when(kioskPaySettings.kopokopoCredentials())
                .thenReturn(Optional.of(Map.of("clientId", "c", "tillNumber", "123456")));

        PlatformMpesaCustodySettingsResponse res =
                service.update(new UpdatePlatformMpesaCustodySettingsRequest("KOPOKOPO"));

        assertThat(res.custodyProvider()).isEqualTo(PlatformMpesaCustodyProviders.KOPOKOPO);
        assertThat(res.kopokopoReady()).isTrue();
    }

    @Test
    void update_darajaBlockedUntilDisburseShips() {
        when(darajaSettings.isEnabledAndConfigured()).thenReturn(true);

        assertThatThrownBy(() ->
                service.update(new UpdatePlatformMpesaCustodySettingsRequest("DARAJA")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void update_invalidProviderRejected() {
        assertThatThrownBy(() ->
                service.update(new UpdatePlatformMpesaCustodySettingsRequest("PAYSTACK")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void availability_reflectsActiveProviderReadiness() {
        when(repository.findById(PlatformMpesaCustodySettings.SINGLETON_ID))
                .thenReturn(Optional.of(settings(PlatformMpesaCustodyProviders.KOPOKOPO)));
        when(kioskPaySettings.kopokopoCredentials())
                .thenReturn(Optional.of(Map.of("clientId", "c")));

        assertThat(service.availabilityForTenant().available()).isTrue();
        assertThat(service.custodyAvailableForTenants()).isTrue();
    }

    @Test
    void availability_falseWhenOff() {
        assertThat(service.availabilityForTenant().available()).isFalse();
        assertThat(service.availabilityForTenant().message()).isNotBlank();
    }

    private static PlatformMpesaCustodySettings settings(String provider) {
        PlatformMpesaCustodySettings row = new PlatformMpesaCustodySettings();
        row.setId(PlatformMpesaCustodySettings.SINGLETON_ID);
        row.setCustodyProvider(provider);
        return row;
    }
}
