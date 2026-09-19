package zelisline.ub.payments.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.payments.api.dto.UpdatePlatformDarajaSettingsRequest;
import zelisline.ub.payments.api.dto.UpdatePlatformMpesaCustodySettingsRequest;
import zelisline.ub.payments.domain.PlatformDarajaSettings;
import zelisline.ub.payments.domain.PlatformMpesaCustodyProviders;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.infrastructure.DarajaPaymentGateway;
import zelisline.ub.payments.repository.PlatformDarajaSettingsRepository;

/**
 * Clearing Daraja B2B must never leave Daraja selected as the custody provider.
 */
@SuppressWarnings("unchecked")
class PlatformDarajaSettingsServiceTest {

    private PlatformDarajaSettingsRepository repository;
    private PlatformMpesaCustodySettingsService custodySettings;
    private ObjectProvider<PlatformMpesaCustodySettingsService> custodyProvider;

    private PlatformDarajaSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformDarajaSettingsRepository.class);
        CredentialEncryptionService encryption = mock(CredentialEncryptionService.class);
        custodySettings = mock(PlatformMpesaCustodySettingsService.class);
        custodyProvider = mock(ObjectProvider.class);

        service = new PlatformDarajaSettingsService(
                repository,
                encryption,
                new ObjectMapper(),
                mock(DarajaPaymentGateway.class),
                custodyProvider);

        PlatformDarajaSettings row = new PlatformDarajaSettings();
        row.setId(PlatformDarajaSettings.SINGLETON_ID);
        // Disabled so the enable-time credential validation is skipped in this unit test.
        row.setEnabled(false);
        row.setShortcode("600000");
        when(repository.findById(PlatformDarajaSettings.SINGLETON_ID)).thenReturn(Optional.of(row));
        when(repository.save(any(PlatformDarajaSettings.class))).thenAnswer(inv -> inv.getArgument(0));
        when(custodyProvider.getIfAvailable()).thenReturn(custodySettings);
    }

    @Test
    void clearDisburseCredentials_resetsDarajaCustodyToOff() {
        when(custodySettings.activeProvider()).thenReturn(PlatformMpesaCustodyProviders.DARAJA);

        service.update(clearDisburse());

        verify(custodySettings).update(any(UpdatePlatformMpesaCustodySettingsRequest.class));
    }

    @Test
    void clearDisburseCredentials_leavesOtherCustodyProviderUntouched() {
        when(custodySettings.activeProvider()).thenReturn(PlatformMpesaCustodyProviders.KOPOKOPO);

        service.update(clearDisburse());

        verify(custodySettings, never()).update(any(UpdatePlatformMpesaCustodySettingsRequest.class));
    }

    private static UpdatePlatformDarajaSettingsRequest clearDisburse() {
        return new UpdatePlatformDarajaSettingsRequest(
                null, null, null, null, null, null, null,
                null, null, null, null,
                Boolean.TRUE, null);
    }
}
