package zelisline.ub.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.platform.api.dto.UpdateMediaStorageSettingsRequest;
import zelisline.ub.platform.domain.PlatformMediaStorageSettings;
import zelisline.ub.platform.media.R2BucketProbe;
import zelisline.ub.platform.media.R2Connection;
import zelisline.ub.platform.repository.PlatformMediaStorageSettingsRepository;

class MediaStorageSettingsServiceTest {

    private PlatformMediaStorageSettingsRepository repository;
    private CredentialEncryptionService encryption;
    private R2BucketProbe probe;
    private MediaStorageSettingsService service;
    private PlatformMediaStorageSettings row;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformMediaStorageSettingsRepository.class);
        encryption = mock(CredentialEncryptionService.class);
        probe = mock(R2BucketProbe.class);
        service = new MediaStorageSettingsService(repository, encryption, probe);

        row = new PlatformMediaStorageSettings();
        row.setId(PlatformMediaStorageSettings.SINGLETON_ID);
        when(repository.findById(PlatformMediaStorageSettings.SINGLETON_ID)).thenReturn(Optional.of(row));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(encryption.encryptSecret(anyString())).thenAnswer(invocation -> "enc:" + invocation.getArgument(0));
        when(encryption.decrypt(anyString())).thenAnswer(invocation ->
                ((String) invocation.getArgument(0)).substring("enc:".length()));
    }

    private static UpdateMediaStorageSettingsRequest fullR2(String provider) {
        return new UpdateMediaStorageSettingsRequest(
                provider, "acct", null, "picshare-media", "key", "secret", "https://media.example.com/");
    }

    private static UpdateMediaStorageSettingsRequest providerOnly(String provider) {
        return new UpdateMediaStorageSettingsRequest(provider, null, null, null, null, null, null);
    }

    @Test
    void defaultsToCloudinaryWithNoActiveR2() {
        when(repository.findById(PlatformMediaStorageSettings.SINGLETON_ID)).thenReturn(Optional.empty());

        assertThat(service.getForSuperAdmin().uploadProvider()).isEqualTo("cloudinary");
        assertThat(service.activeR2Connection()).isEmpty();
    }

    @Test
    void savesR2KeysEncryptedWithoutSwitchingOrProbing() {
        var response = service.update(fullR2(null));

        assertThat(row.getR2AccessKeyIdEnc()).isEqualTo("enc:key");
        assertThat(row.getR2SecretAccessKeyEnc()).isEqualTo("enc:secret");
        assertThat(row.getR2PublicBaseUrl()).isEqualTo("https://media.example.com");
        assertThat(response.uploadProvider()).isEqualTo("cloudinary");
        assertThat(response.hasR2SecretAccessKey()).isTrue();
        assertThat(response.r2Active()).isFalse();
        verify(probe, never()).verify(any());
    }

    @Test
    void switchingToR2ProbesTheBucketThenActivatesIt() {
        service.update(fullR2(null));

        var response = service.update(providerOnly("R2"));

        verify(probe).verify(new R2Connection("https://acct.r2.cloudflarestorage.com",
                "picshare-media", "key", "secret", "https://media.example.com"));
        assertThat(response.r2Active()).isTrue();
        assertThat(service.activeR2Connection()).isPresent();
    }

    @Test
    void refusesToSwitchWhenSettingsAreIncomplete() {
        assertThatThrownBy(() -> service.update(providerOnly("r2")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("bucket is missing")
                .hasMessageContaining("secret access key is missing");
        verify(repository, never()).save(any());
    }

    @Test
    void refusesToSwitchWhenTheBucketIsNotWritable() {
        service.update(fullR2(null));
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "R2 bucket is not writable: denied"))
                .when(probe).verify(any());

        assertThatThrownBy(() -> service.update(providerOnly("r2"))).hasMessageContaining("not writable");
    }

    @Test
    void rejectsUnknownProviders() {
        assertThatThrownBy(() -> service.update(providerOnly("s3"))).hasMessageContaining("cloudinary or r2");
    }

    @Test
    void fallsBackToCloudinaryWhenStoredKeysCannotBeDecrypted() {
        service.update(fullR2("r2"));
        doThrow(new IllegalStateException("bad key")).when(encryption).decrypt(anyString());

        assertThat(service.activeR2Connection()).isEmpty();
        var response = service.getForSuperAdmin();
        assertThat(response.r2Active()).isFalse();
        assertThat(response.secretsReadable()).isFalse();
    }

    @Test
    void switchingBackToCloudinaryNeedsNoProbe() {
        service.update(fullR2("r2"));

        var response = service.update(providerOnly("cloudinary"));

        assertThat(response.uploadProvider()).isEqualTo("cloudinary");
        assertThat(service.activeR2Connection()).isEmpty();
        verify(probe).verify(any());
    }
}
