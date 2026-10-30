package zelisline.ub.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.payments.api.dto.UpdatePlatformKioskPaySettingsRequest;
import zelisline.ub.payments.domain.PlatformKioskPaySettings;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.repository.PlatformKioskPaySettingsRepository;

class PlatformKioskPaySettingsServiceTest {

    private static final String KOPOKOPO_SECRET =
            "g7QLwvO37aN2HoKx1amekWi8a2g7AIuPbD5CcJSLqXIcDOxfTr";

    private PlatformKioskPaySettingsRepository repository;
    private CredentialEncryptionService encryption;
    private PlatformKioskPaySettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformKioskPaySettingsRepository.class);
        encryption = new CredentialEncryptionService(null);
        service = new PlatformKioskPaySettingsService(repository, encryption, new ObjectMapper());
        PlatformKioskPaySettings row = new PlatformKioskPaySettings();
        when(repository.findById(PlatformKioskPaySettings.SINGLETON_ID)).thenReturn(Optional.of(row));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void kopokopoClientSecretIsNotRequiredToUsePaystackPrefix() {
        service.update(request(
                KOPOKOPO_SECRET,
                "client-id",
                KOPOKOPO_SECRET,
                "api-key",
                "3502582"));

        PlatformKioskPaySettings saved = savedRow();
        String kopokopo = encryption.decrypt(saved.getKopokopoCredentialsEnc());
        assertThat(kopokopo).contains(KOPOKOPO_SECRET);
        assertThat(kopokopo).contains("client-id");
        assertThat(saved.getPaystackCredentialsEnc()).isNull();
    }

    @Test
    void paystackPrefixCheckStillRejectsAPaystackSecret() {
        assertThatThrownBy(() -> service.update(request(
                "not_a_paystack_key",
                null,
                null,
                null,
                null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private PlatformKioskPaySettings savedRow() {
        ArgumentCaptor<PlatformKioskPaySettings> captor =
                ArgumentCaptor.forClass(PlatformKioskPaySettings.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static UpdatePlatformKioskPaySettingsRequest request(
            String paystackSecret,
            String clientId,
            String clientSecret,
            String apiKey,
            String till
    ) {
        return new UpdatePlatformKioskPaySettingsRequest(
                true,
                null,
                null,
                null,
                null,
                "sandbox",
                null,
                paystackSecret,
                null,
                "sandbox",
                clientId,
                clientSecret,
                apiKey,
                till,
                null,
                null);
    }
}
