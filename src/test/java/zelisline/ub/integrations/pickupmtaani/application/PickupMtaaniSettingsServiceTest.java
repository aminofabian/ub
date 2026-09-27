package zelisline.ub.integrations.pickupmtaani.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.integrations.pickupmtaani.api.dto.PickupMtaaniPatchRequest;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.GeoOption;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

class PickupMtaaniSettingsServiceTest {

    private static final String BUSINESS_ID = "biz-1";
    private static final String KEY = "pm-key-123";
    private static final String ENC = "enc-blob";

    private final PickupMtaaniSettingsJson json = new PickupMtaaniSettingsJson(new ObjectMapper());
    private final CredentialEncryptionService encryptionService = mock(CredentialEncryptionService.class);
    private final BusinessRepository businessRepository = mock(BusinessRepository.class);
    private final PickupMtaaniClient client = mock(PickupMtaaniClient.class);

    private final PickupMtaaniSettingsService service =
            new PickupMtaaniSettingsService(json, encryptionService, businessRepository, client);

    private Business business;

    @BeforeEach
    void setUp() {
        business = new Business();
        business.setId(BUSINESS_ID);
        business.setName("Shop");
        business.setSettings("{}");
        when(businessRepository.findByIdAndDeletedAtIsNull(BUSINESS_ID)).thenReturn(Optional.of(business));
        when(businessRepository.save(any(Business.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encryptionService.decrypt(ENC)).thenReturn(KEY);
    }

    @Test
    void readFromSettingsJson_blank_returnsDisconnectedDefaults() {
        var response = service.readFromSettingsJson("");

        assertThat(response.enabled()).isFalse();
        assertThat(response.businessName()).isNull();
        assertThat(response.feeMode()).isEqualTo("pass_through");
        assertThat(response.agent()).isTrue();
        assertThat(response.doorstep()).isTrue();
        assertThat(response.bookOnDispatch()).isTrue();
        assertThat(response.status()).isEqualTo("disconnected");
        assertThat(response.ready()).isFalse();
    }

    @Test
    void readFromSettingsJson_readyRequiresKeySingleBusinessAndOrigin() {
        String ready = """
                {"pickupMtaani":{"enabled":true,"apiKeyEnc":"enc-blob","accountMode":"single_business",
                 "originAgentId":362,"originAgentName":"Kahawa West","feeMode":"absorb","markupKes":0}}
                """;
        assertThat(service.readFromSettingsJson(ready).ready()).isTrue();
        assertThat(service.readFromSettingsJson(ready).businessName()).isNull();

        String noOrigin = "{\"pickupMtaani\":{\"enabled\":true,\"apiKeyEnc\":\"enc-blob\","
                + "\"accountMode\":\"single_business\"}}";
        assertThat(service.readFromSettingsJson(noOrigin).ready()).isFalse();

        String multi = "{\"pickupMtaani\":{\"enabled\":true,\"apiKeyEnc\":\"enc-blob\","
                + "\"accountMode\":\"multi_business\",\"originAgentId\":362}}";
        assertThat(service.readFromSettingsJson(multi).ready()).isFalse();
    }

    @Test
    void update_rejectsMerchantSuppliedApiKey() {
        assertThatThrownBy(() -> service.update(BUSINESS_ID, new PickupMtaaniPatchRequest(
                true, KEY, null, null, null, null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("managed by Palmart support");
        verify(encryptionService, never()).encryptSecret(any());
    }

    @Test
    void update_configOnly_preservesSiblingsAndSetsOrigin() throws Exception {
        business.setSettings("{\"storefront\":{\"theme\":\"light\"},\"pickupMtaani\":{\"enabled\":false}}");

        var response = service.update(BUSINESS_ID, new PickupMtaaniPatchRequest(
                true, null, "absorb", null, false, true, false, 362L, "Kahawa West", "Kahawa"));

        var root = new ObjectMapper().readTree(business.getSettings());
        assertThat(root.path("storefront").path("theme").asText()).isEqualTo("light");
        assertThat(root.path("pickupMtaani").path("originAgentId").asLong()).isEqualTo(362L);
        assertThat(root.path("pickupMtaani").path("feeMode").asText()).isEqualTo("absorb");
        assertThat(root.path("pickupMtaani").path("agent").asBoolean()).isFalse();
        assertThat(root.path("pickupMtaani").path("doorstep").asBoolean()).isTrue();
        assertThat(response.enabled()).isTrue();
        verify(client, never()).getAccount(any());
    }

    @Test
    void update_markupWithoutAmount_rejected() {
        assertThatThrownBy(() -> service.update(BUSINESS_ID, new PickupMtaaniPatchRequest(
                null, null, "markup", null, null, null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("markupKes");
    }

    @Test
    void update_invalidFeeMode_rejected() {
        assertThatThrownBy(() -> service.update(BUSINESS_ID, new PickupMtaaniPatchRequest(
                null, null, "cheapest", null, null, null, null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("feeMode");
    }

    @Test
    void readPublicConfig_kesOnlyAndReadyOnly() {
        String ready = """
                {"pickupMtaani":{"enabled":true,"apiKeyEnc":"enc-blob","accountMode":"single_business",
                 "originAgentId":362,"originAgentName":"Kahawa West Agent","agent":true,"doorstep":true}}
                """;

        assertThat(service.readPublicConfig(ready, "USD")).isNull();
        assertThat(service.readPublicConfig(ready, "KES")).isNotNull();
        assertThat(service.readPublicConfig(ready, "KES").originLabel()).isEqualTo("Kahawa West Agent");

        String notReady = "{\"pickupMtaani\":{\"enabled\":false,\"apiKeyEnc\":\"enc-blob\","
                + "\"accountMode\":\"single_business\",\"originAgentId\":362}}";
        assertThat(service.readPublicConfig(notReady, "KES")).isNull();
    }

    @Test
    void resolve_decryptsAndReportsReadiness() {
        business.setSettings("""
                {"pickupMtaani":{"enabled":true,"apiKeyEnc":"enc-blob","accountMode":"single_business",
                 "originAgentId":362,"originAgentName":"Kahawa West","feeMode":"markup","markupKes":20}}
                """);

        var resolved = service.resolve(BUSINESS_ID);

        assertThat(resolved.apiKey()).isEqualTo(KEY);
        assertThat(resolved.ready()).isTrue();
        assertThat(resolved.originAgentId()).isEqualTo(362L);
        assertThat(resolved.markupKes()).isEqualTo(20);
    }

    @Test
    void listZones_notConnected_conflict() {
        assertThatThrownBy(() -> service.listZones(BUSINESS_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not connected");
    }

    @Test
    void listZones_connected_mapsOptions() {
        business.setSettings("{\"pickupMtaani\":{\"apiKeyEnc\":\"enc-blob\"}}");
        when(client.listZones(KEY)).thenReturn(List.of(
                new GeoOption(1L, "Nairobi", null, null, null)));

        var zones = service.listZones(BUSINESS_ID);

        assertThat(zones).hasSize(1);
        assertThat(zones.get(0).name()).isEqualTo("Nairobi");
    }
}
