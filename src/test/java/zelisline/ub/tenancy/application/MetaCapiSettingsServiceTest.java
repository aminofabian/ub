package zelisline.ub.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.tenancy.api.dto.MetaCapiPatchRequest;

class MetaCapiSettingsServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CredentialEncryptionService encryptionService = mock(CredentialEncryptionService.class);
    private final MetaCapiSettingsService service = new MetaCapiSettingsService(objectMapper, encryptionService);

    @Test
    void readFromSettingsJson_blankSettings_returnsEmpty() {
        var response = service.readFromSettingsJson("");

        assertThat(response.enabled()).isNull();
        assertThat(response.pixelId()).isNull();
        assertThat(response.hasAccessToken()).isFalse();
        assertThat(response.consentRequired()).isNull();
    }

    @Test
    void readFromSettingsJson_parsesConfiguredValues() {
        String settings = """
                {"metaCapi":{"enabled":true,"pixelId":"123456789","accessTokenEnc":"enc-abc","consentRequired":true}}
                """;

        var response = service.readFromSettingsJson(settings);

        assertThat(response.enabled()).isTrue();
        assertThat(response.pixelId()).isEqualTo("123456789");
        assertThat(response.hasAccessToken()).isTrue();
        assertThat(response.consentRequired()).isTrue();
    }

    @Test
    void merge_appliesPatchAndPreservesSiblingNamespaces() throws Exception {
        String current = """
                {"storefront":{"theme":"light"},"metaCapi":{"enabled":false}}
                """;

        String merged = service.merge(
                current,
                new MetaCapiPatchRequest(true, "987654321", null, null, false));

        var root = objectMapper.readTree(merged);
        assertThat(root.path("metaCapi").path("enabled").asBoolean()).isTrue();
        assertThat(root.path("metaCapi").path("pixelId").asText()).isEqualTo("987654321");
        assertThat(root.path("metaCapi").path("consentRequired").asBoolean()).isFalse();
        // sibling namespace untouched
        assertThat(root.path("storefront").path("theme").asText()).isEqualTo("light");
    }

    @Test
    void merge_encryptsAccessTokenAtRest() {
        when(encryptionService.encryptSecret("tok-123")).thenReturn("encrypted-blob");

        String merged = service.merge("{}", new MetaCapiPatchRequest(null, null, "tok-123", null, null));

        assertThat(merged).contains("encrypted-blob");
        assertThat(merged).doesNotContain("tok-123");
        verify(encryptionService).encryptSecret("tok-123");
        assertThat(service.readFromSettingsJson(merged).hasAccessToken()).isTrue();
    }

    @Test
    void merge_blankAccessTokenClearsStoredToken() {
        String current = "{\"metaCapi\":{\"accessTokenEnc\":\"enc-abc\"}}";

        String merged = service.merge(current, new MetaCapiPatchRequest(null, null, "  ", null, null));

        assertThat(merged).doesNotContain("accessTokenEnc");
        assertThat(service.readFromSettingsJson(merged).hasAccessToken()).isFalse();
    }

    @Test
    void merge_blankPixelIdClearsIt() {
        String current = "{\"metaCapi\":{\"pixelId\":\"123456789\"}}";

        String merged = service.merge(current, new MetaCapiPatchRequest(null, "", null, null, null));

        assertThat(service.readFromSettingsJson(merged).pixelId()).isNull();
    }

    @Test
    void merge_nullPatchFieldsLeaveValuesUnchanged() {
        String current = "{\"metaCapi\":{\"enabled\":true,\"pixelId\":\"123456789\"}}";

        String merged = service.merge(current, new MetaCapiPatchRequest(null, null, null, null, null));

        assertThat(merged).isSameAs(current);
    }

    @Test
    void readPublicPixelConfig_unconfigured_isDisabled() {
        var config = service.readPublicPixelConfig("{}");

        assertThat(config.enabled()).isFalse();
        assertThat(config.pixelId()).isNull();
    }

    @Test
    void readPublicPixelConfig_enabledOnlyWhenEnabledAndPixelIdPresent() {
        assertThat(service.readPublicPixelConfig("{\"metaCapi\":{\"enabled\":true}}").enabled()).isFalse();
        assertThat(service.readPublicPixelConfig("{\"metaCapi\":{\"pixelId\":\"123456789\"}}").enabled()).isFalse();

        var config = service.readPublicPixelConfig("{\"metaCapi\":{\"enabled\":true,\"pixelId\":\"123456789\"}}");

        assertThat(config.enabled()).isTrue();
        assertThat(config.pixelId()).isEqualTo("123456789");
    }
}
