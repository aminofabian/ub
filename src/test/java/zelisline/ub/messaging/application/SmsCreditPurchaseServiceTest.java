package zelisline.ub.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import zelisline.ub.messaging.api.dto.SmsCreditPurchaseDtos;
import zelisline.ub.messaging.domain.PlatformSmsCreditSettings;
import zelisline.ub.messaging.domain.SmsCreditPurchase;
import zelisline.ub.messaging.domain.SmsCreditPurchaseStatus;
import zelisline.ub.messaging.repository.SmsCreditPurchaseRepository;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.application.PaymentGatewayStkService;
import zelisline.ub.payments.domain.GatewayStkPush;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.StkPushContextType;

/**
 * SMS-credit top-ups prompt on the platform Daraja app.
 */
class SmsCreditPurchaseServiceTest {

    private static final String BUSINESS_ID = "biz-1";

    private final SmsCreditPurchaseRepository purchaseRepository =
            mock(SmsCreditPurchaseRepository.class);
    private final SmsCreditSettingsService settingsService = mock(SmsCreditSettingsService.class);
    private final SmsCreditService creditService = mock(SmsCreditService.class);
    private final PaymentGatewayStkService paymentGatewayStkService =
            mock(PaymentGatewayStkService.class);
    private final GatewayStkPushService gatewayStkPushService = mock(GatewayStkPushService.class);

    private final SmsCreditPurchaseService service = new SmsCreditPurchaseService(
            purchaseRepository,
            settingsService,
            creditService,
            paymentGatewayStkService,
            gatewayStkPushService);

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
    void smsCreditsUsePlatformDaraja() {
        given(paymentGatewayStkService.initiateToPlatformDestination(
                anyString(), anyString(), any(BigDecimal.class), anyString(), anyString()))
                .willReturn(PaymentGatewayStkService.StkPushOutcome.accepted(
                        "DARAJA", "platform-daraja",
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

        verify(paymentGatewayStkService).initiateToPlatformDestination(
                eq(BUSINESS_ID),
                eq("254721530181"),
                eq(new BigDecimal("80.00")),
                anyString(),
                anyString());

        verify(gatewayStkPushService).registerPush(
                eq(BUSINESS_ID),
                eq(GatewayType.DARAJA),
                eq("platform-daraja"),
                eq("ws_CO_1"),
                anyString(),
                eq(StkPushContextType.SMS_CREDIT_PURCHASE),
                anyString(),
                eq(new BigDecimal("80.00")),
                eq("254721530181"));
    }

    @Test
    void smsCreditsRecordTheGatewayFailureInsteadOfAPlatformDarajaLogin() {
        given(paymentGatewayStkService.initiateToPlatformDestination(
                anyString(), anyString(), any(BigDecimal.class), anyString(), anyString()))
                .willReturn(PaymentGatewayStkService.StkPushOutcome.rejected(
                        "DARAJA", "DECLINED", "The customer cancelled the request"));

        SmsCreditPurchaseDtos.SmsCreditPurchaseResponse response =
                service.initiate(BUSINESS_ID, 100, "0721530181");

        assertThat(response.status()).isEqualTo(SmsCreditPurchaseStatus.FAILED);
        assertThat(response.message()).isEqualTo("The customer cancelled the request");
        verify(gatewayStkPushService, never()).registerPush(
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), any(), anyString());
    }
}
