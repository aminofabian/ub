package zelisline.ub.integrations.pickupmtaani.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniApiException;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.AccountInfo;
import zelisline.ub.integrations.pickupmtaani.infrastructure.PickupMtaaniClient.BusinessInfo;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

class PickupMtaaniCredentialServiceTest {

    private static final String BUSINESS_ID = "biz-1";
    private static final String KEY = "pm-key-123";
    private static final String ENC = "enc-blob";

    private final PickupMtaaniSettingsJson json = new PickupMtaaniSettingsJson(new ObjectMapper());
    private final CredentialEncryptionService encryptionService = mock(CredentialEncryptionService.class);
    private final BusinessRepository businessRepository = mock(BusinessRepository.class);
    private final PickupMtaaniClient client = mock(PickupMtaaniClient.class);

    private final PickupMtaaniCredentialService service =
            new PickupMtaaniCredentialService(json, encryptionService, businessRepository, client);

    private Business business;

    @BeforeEach
    void setUp() {
        business = new Business();
        business.setId(BUSINESS_ID);
        business.setName("Shop");
        business.setSettings("{}");
        when(businessRepository.findByIdAndDeletedAtIsNull(BUSINESS_ID)).thenReturn(Optional.of(business));
        when(businessRepository.save(any(Business.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encryptionService.encryptSecret(KEY)).thenReturn(ENC);
        when(encryptionService.decrypt(ENC)).thenReturn(KEY);
    }

    @Test
    void save_singleBusiness_encryptsBindsAndConnects() {
        when(client.getAccount(KEY)).thenReturn(new AccountInfo("single_business", 1, 1));
        when(client.getBusiness(KEY)).thenReturn(new BusinessInfo(99L, "PUM Shop", "0712", "PIN"));

        var response = service.save(BUSINESS_ID, KEY);

        assertThat(response.hasApiKey()).isTrue();
        assertThat(response.businessId()).isEqualTo(99L);
        assertThat(response.businessName()).isEqualTo("PUM Shop");
        assertThat(response.accountMode()).isEqualTo("single_business");
        assertThat(response.status()).isEqualTo("connected");
        assertThat(response.lastVerifiedAt()).isNotBlank();
        assertThat(business.getSettings()).contains(ENC).doesNotContain(KEY);
        verify(encryptionService).encryptSecret(KEY);
    }

    @Test
    void save_multiBusinessKey_flagsErrorAndSkipsBusinessLookup() {
        when(client.getAccount(KEY)).thenReturn(new AccountInfo("multi_business", 3, 5));

        var response = service.save(BUSINESS_ID, KEY);

        assertThat(response.hasApiKey()).isTrue();
        assertThat(response.accountMode()).isEqualTo("multi_business");
        assertThat(response.status()).isEqualTo("error");
        assertThat(response.statusDetail()).contains("one business");
        assertThat(business.getSettings()).contains("\"enabled\":false");
        verify(client, never()).getBusiness(any());
    }

    @Test
    void save_upstreamFailure_keepsKeyAndRecordsError() {
        when(client.getAccount(KEY)).thenThrow(new PickupMtaaniApiException(401, "AUTH", "req-1", "Invalid key"));

        var response = service.save(BUSINESS_ID, KEY);

        assertThat(response.hasApiKey()).isTrue();
        assertThat(response.status()).isEqualTo("error");
        assertThat(response.statusDetail()).isEqualTo("Invalid key");
        assertThat(business.getSettings()).contains(ENC);
    }

    @Test
    void save_blankKey_rejected() {
        assertThatThrownBy(() -> service.save(BUSINESS_ID, "  "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("apiKey is required");
    }

    @Test
    void verify_withoutKey_conflict() {
        assertThatThrownBy(() -> service.verify(BUSINESS_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("before verifying");
    }

    @Test
    void verify_withStoredKey_reconnects() {
        business.setSettings("{\"pickupMtaani\":{\"apiKeyEnc\":\"enc-blob\",\"accountMode\":\"single_business\"}}");
        when(client.getAccount(KEY)).thenReturn(new AccountInfo("single_business", 1, 1));
        when(client.getBusiness(KEY)).thenReturn(new BusinessInfo(99L, "PUM Shop", "0712", "PIN"));

        var response = service.verify(BUSINESS_ID);

        assertThat(response.status()).isEqualTo("connected");
        assertThat(response.businessId()).isEqualTo(99L);
    }

    @Test
    void disconnect_clearsCredentialAndDisables() {
        business.setSettings("""
                {"pickupMtaani":{"enabled":true,"apiKeyEnc":"enc-blob","businessId":99,
                 "businessName":"PUM Shop","accountMode":"single_business","originAgentId":362,
                 "feeMode":"markup","markupKes":50}}
                """);

        var response = service.disconnect(BUSINESS_ID);

        assertThat(response.hasApiKey()).isFalse();
        assertThat(response.status()).isEqualTo("disconnected");
        assertThat(business.getSettings()).doesNotContain("enc-blob");
        // merchant option preferences survive
        assertThat(business.getSettings()).contains("originAgentId").contains("markup");
        assertThat(business.getSettings()).contains("\"enabled\":false");
    }

    @Test
    void read_withoutKey_disconnected() {
        assertThat(service.read(BUSINESS_ID).hasApiKey()).isFalse();
    }
}
