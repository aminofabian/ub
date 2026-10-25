package zelisline.ub.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.messaging.api.dto.SmsCreditPurchaseDtos;
import zelisline.ub.messaging.domain.PlatformSmsCreditSettings;
import zelisline.ub.messaging.domain.SmsCreditPurchase;
import zelisline.ub.messaging.domain.SmsCreditPurchaseStatus;
import zelisline.ub.messaging.repository.SmsCreditPurchaseRepository;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.application.PaymentGatewayStkService;
import zelisline.ub.payments.application.PlatformStkRailService;
import zelisline.ub.payments.domain.GatewayStkPush;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PlatformDarajaSettings;
import zelisline.ub.payments.domain.StkPushContextType;

/**
 * SMS-credit top-ups must collect on the same platform M-Pesa rail package
 * renewals use (Daraja Express when Super Admin made it live), instead of the
 * hard-coded Palmart KopoKopo till that produced the {@code invalid_client}
 * OAuth failure.
 */
@SuppressWarnings("unchecked")
class SmsCreditPurchaseServiceTest {

    private static final String BUSINESS_ID = "biz-1";

    private final SmsCreditPurchaseRepository purchaseRepository =
            mock(SmsCreditPurchaseRepository.class);
    private final SmsCreditSettingsService settingsService = mock(SmsCreditSettingsService.class);
    private final SmsCreditService creditService = mock(SmsCreditService.class);
    private final PaymentGatewayStkService paymentGatewayStkService =
            mock(PaymentGatewayStkService.class);
    private final GatewayStkPushService gatewayStkPushService = mock(GatewayStkPushService.class);
    private final PlatformStkRailService platformStkRailService = mock(PlatformStkRailService.class);

    private final SmsCreditPurchaseService service = new SmsCreditPurchaseService(
            purchaseRepository,
            settingsService,
            creditService,
            paymentGatewayStkService,
            gatewayStkPushService,
            platformStkRailService);

    @BeforeEach
    void setUp() {
        PlatformSmsCreditSettings settings = new PlatformSmsCreditSettings();
        settings.setEnabled(true);
        settings.setUnitPriceKes(new BigDecimal("0.80"));
        settings.setMinPurchaseCredits(10);
        settings.setMaxPurchaseCredits(500);
        given(settingsService.loadSingleton()).willReturn(settings);

        given(purchaseRepository.save(any())).willAnswer(invocation -> {
            var purchase = (SmsCreditPurchase) invocation.getArgument(0);
            if (purchase.getId() == null) {
                purchase.setId(UUID.randomUUID().toString());
            }
            return purchase;
        });
    }

    @Test
    void smsCreditsCollectOnPlatformDarajaRail() {
        given(platformStkRailService.require(anyString())).willReturn(new PlatformStkRailService.Rail(
                GatewayType.DARAJA,
                PlatformDarajaSettings.PLATFORM_DARAJA_CONFIG_ID,
                "Safaricom Daraja (M-Pesa Express)",
                Map.of(
                        "shortcode", "4030000",
                        "passkey", "pk",
                        "consumerKey", "ck",
                        "consumerSecret", "cs",
                        "environment", "production",
                        "shortcodeType", "paybill")));
        given(paymentGatewayStkService.initiateWithCredentials(
                anyString(), anyString(), anyString(), anyMap(), anyString(), any(), anyString(), anyString()))
                .willReturn(PaymentGatewayStkService.StkPushOutcome.accepted(
                        "DARAJA", PlatformDarajaSettings.PLATFORM_DARAJA_CONFIG_ID,
                        "ws_CO_1", "Check your phone to complete M-Pesa payment."));
        GatewayStkPush push = new GatewayStkPush();
        push.setId("push-1");
        given(gatewayStkPushService.registerPush(
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), any(), anyString()))
                .willReturn(push);

        SmsCreditPurchaseDtos.SmsCreditPurchaseResponse response =
                service.initiate(BUSINESS_ID, 100, "0721530181");

        assertThat(response.status()).isEqualTo(SmsCreditPurchaseStatus.PENDING);
        assertThat(response.amountKes()).isEqualByComparingTo("80.00");

        ArgumentCaptor<Map<String, String>> credentials = ArgumentCaptor.forClass(Map.class);
        verify(paymentGatewayStkService).initiateWithCredentials(
                eq("DARAJA"),
                eq(PlatformDarajaSettings.PLATFORM_DARAJA_CONFIG_ID),
                eq(BUSINESS_ID),
                credentials.capture(),
                eq("254721530181"),
                eq(new BigDecimal("80.00")),
                anyString(),
                anyString());
        assertThat(credentials.getValue()).containsEntry("shortcode", "4030000");
        assertThat(credentials.getValue().get("accountReference")).hasSize(12);

        verify(gatewayStkPushService).registerPush(
                eq(BUSINESS_ID),
                eq(GatewayType.DARAJA),
                eq(PlatformDarajaSettings.PLATFORM_DARAJA_CONFIG_ID),
                eq("ws_CO_1"),
                anyString(),
                eq(StkPushContextType.SMS_CREDIT_PURCHASE),
                anyString(),
                eq(new BigDecimal("80.00")),
                eq("254721530181"));
    }

    @Test
    void smsCreditsStopBeforeCreatingAPurchaseWhenNoRailIsConfigured() {
        given(platformStkRailService.require(anyString())).willThrow(new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Platform M-Pesa is not configured. Ask Super Admin to enable Safaricom Daraja."));

        assertThatThrownBy(() -> service.initiate(BUSINESS_ID, 100, "0721530181"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        verify(purchaseRepository, never()).save(any());
        verify(paymentGatewayStkService, never()).initiateWithCredentials(
                anyString(), anyString(), anyString(), anyMap(), anyString(), any(), anyString(), anyString());
    }
}
