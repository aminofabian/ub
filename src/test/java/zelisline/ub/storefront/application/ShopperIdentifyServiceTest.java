package zelisline.ub.storefront.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.credits.api.dto.SendCustomerPhoneVerificationResponse;
import zelisline.ub.credits.api.dto.VerifyCustomerPhoneVerificationResponse;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.credits.application.CustomerPhoneVerificationService;
import zelisline.ub.credits.domain.CustomerPhoneVerification;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerPhoneVerificationRepository;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.infrastructure.RapidApiWhatsAppLookupClient;
import zelisline.ub.tenancy.api.dto.PublicShopsSearchResponse;
import zelisline.ub.tenancy.application.PublicShopsSearchService;

class ShopperIdentifyServiceTest {

    private CustomerPhoneVerificationRepository verificationRepository;
    private CustomerPhoneRepository customerPhoneRepository;
    private CustomerPhoneVerificationService phoneVerificationService;
    private BusinessCreditMessagingSettingsService messagingSettingsService;
    private CustomerMessageDispatcher customerMessageDispatcher;
    private PublicShopsSearchService publicShopsSearchService;
    private ShopperIdentifyService service;

    @BeforeEach
    void setUp() {
        verificationRepository = Mockito.mock(CustomerPhoneVerificationRepository.class);
        customerPhoneRepository = Mockito.mock(CustomerPhoneRepository.class);
        phoneVerificationService = Mockito.mock(CustomerPhoneVerificationService.class);
        messagingSettingsService = Mockito.mock(BusinessCreditMessagingSettingsService.class);
        customerMessageDispatcher = Mockito.mock(CustomerMessageDispatcher.class);
        publicShopsSearchService = Mockito.mock(PublicShopsSearchService.class);
        service = new ShopperIdentifyService(
                verificationRepository,
                customerPhoneRepository,
                phoneVerificationService,
                messagingSettingsService,
                customerMessageDispatcher,
                publicShopsSearchService,
                Mockito.mock(zelisline.ub.tenancy.application.PublicSignInDestinationService.class));
    }

    @Test
    void sendCodeStoresChallengeAndDeliversViaPlatformConfig() {
        TenantMessagingConfig config = Mockito.mock(TenantMessagingConfig.class);
        Mockito.when(config.secretsReadable()).thenReturn(true);
        Mockito.when(config.metaWhatsAppConfigured()).thenReturn(true);
        Mockito.when(messagingSettingsService.resolvePlatformForContactReply()).thenReturn(config);
        Mockito.when(customerMessageDispatcher.deliverBothChannels(
                        Mockito.eq(config), Mockito.eq("0714282874"), Mockito.anyString()))
                .thenReturn(new CustomerMessageDispatcher.DeliveryResult(
                        RapidApiWhatsAppLookupClient.LookupResult.lookupSkipped("test"),
                        "sms", "sent", "ok"));

        SendCustomerPhoneVerificationResponse response = service.sendCode("0714 282 874");

        assertThat(response.phone()).isEqualTo("0714282874");
        assertThat(response.channel()).isEqualTo("sms");
        assertThat(response.maskedHint()).endsWith("2874");

        ArgumentCaptor<CustomerPhoneVerification> captor =
                ArgumentCaptor.forClass(CustomerPhoneVerification.class);
        Mockito.verify(verificationRepository).save(captor.capture());
        CustomerPhoneVerification challenge = captor.getValue();
        assertThat(challenge.getBusinessId()).isEqualTo("platform");
        assertThat(challenge.getPhone()).isEqualTo("0714282874");
        assertThat(challenge.getCodeHash()).isNotBlank();
    }

    @Test
    void resendWithinCooldownIsBlocked() {
        CustomerPhoneVerification open = new CustomerPhoneVerification();
        open.setLastSentAt(Instant.now());
        Mockito.when(verificationRepository
                        .findFirstByBusinessIdAndPhoneAndConsumedAtIsNullOrderByCreatedAtDesc(
                                "platform", "0714282874"))
                .thenReturn(Optional.of(open));

        assertThatThrownBy(() -> service.sendCode("0714282874"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void verifyDelegatesToExistingVerificationService() {
        Instant expires = Instant.now().plusSeconds(600);
        Mockito.when(phoneVerificationService.verify("platform", "0714282874", "1234"))
                .thenReturn(new VerifyCustomerPhoneVerificationResponse("token-1", expires));

        VerifyCustomerPhoneVerificationResponse response = service.verifyCode("0714282874", "1234");

        assertThat(response.phoneVerificationToken()).isEqualTo("token-1");
        assertThat(response.expiresAt()).isEqualTo(expires);
    }

    @Test
    void shopsReturnsRowsForVerifiedPhone() {
        Mockito.when(phoneVerificationService.consumeRegistrationToken("platform", "tok", "0714282874"))
                .thenReturn("0714282874");
        Mockito.when(customerPhoneRepository.findDistinctBusinessIdByPhones(Mockito.any()))
                .thenReturn(List.of("b1", "b2"));
        Mockito.when(publicShopsSearchService.byBusinessIds(List.of("b1", "b2")))
                .thenReturn(List.of(
                        new PublicShopsSearchResponse("acme", "Acme Shop", null, "acme.kiosk.ke")));

        List<PublicShopsSearchResponse> rows = service.shops("0714282874", "tok");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).slug()).isEqualTo("acme");
    }

    @Test
    void shopsReturnsEmptyForVerifiedPhoneWithoutHistory() {
        Mockito.when(phoneVerificationService.consumeRegistrationToken("platform", "tok", "0714282874"))
                .thenReturn("0714282874");
        Mockito.when(customerPhoneRepository.findDistinctBusinessIdByPhones(Mockito.any()))
                .thenReturn(List.of());

        List<PublicShopsSearchResponse> rows = service.shops("0714282874", "tok");

        assertThat(rows).isEmpty();
    }
}
