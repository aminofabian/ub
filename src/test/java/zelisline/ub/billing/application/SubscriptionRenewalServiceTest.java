package zelisline.ub.billing.application;

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
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.billing.api.dto.SubscriptionBillingDtos;
import zelisline.ub.billing.domain.PlatformSubscriptionPlan;
import zelisline.ub.billing.repository.SubscriptionRenewalOrderRepository;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.application.PaymentGatewayStkService;
import zelisline.ub.payments.application.PlatformStkRailService;
import zelisline.ub.payments.domain.GatewayStkPush;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PlatformDarajaSettings;
import zelisline.ub.payments.domain.StkPushContextType;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Package renewals must collect on whichever platform M-Pesa rail Super Admin made
 * live — the Custody rail (Daraja Express) tenant payments use — instead of the
 * hard-coded KopoKopo till that produced the {@code invalid_client} OAuth failure.
 */
@SuppressWarnings("unchecked")
class SubscriptionRenewalServiceTest {

    private static final String BUSINESS_ID = "biz-1";

    private final SubscriptionRenewalOrderRepository orderRepository =
            mock(SubscriptionRenewalOrderRepository.class);
    private final SubscriptionBillingSettingsService settingsService =
            mock(SubscriptionBillingSettingsService.class);
    private final SubscriptionBillingService billingService = mock(SubscriptionBillingService.class);
    private final SubscriptionPricingService pricingService = mock(SubscriptionPricingService.class);
    private final SubscriptionPlanFitService planFitService = mock(SubscriptionPlanFitService.class);
    private final SubscriptionAccessPolicy accessPolicy = mock(SubscriptionAccessPolicy.class);
    private final BusinessRepository businessRepository = mock(BusinessRepository.class);
    private final PaymentGatewayStkService paymentGatewayStkService = mock(PaymentGatewayStkService.class);
    private final GatewayStkPushService gatewayStkPushService = mock(GatewayStkPushService.class);
    private final PlatformStkRailService platformStkRailService = mock(PlatformStkRailService.class);

    private final SubscriptionRenewalService service = new SubscriptionRenewalService(
            orderRepository,
            settingsService,
            billingService,
            pricingService,
            planFitService,
            accessPolicy,
            businessRepository,
            paymentGatewayStkService,
            gatewayStkPushService,
            platformStkRailService);

    @BeforeEach
    void setUp() {
        Business business = new Business();
        business.setId(BUSINESS_ID);
        business.setSubscriptionTier("business");

        PlatformSubscriptionPlan plan = new PlatformSubscriptionPlan();
        plan.setTierCode("business");
        plan.setDisplayName("Business");
        plan.setMonthlyPriceKes(new BigDecimal("800.00"));
        plan.setSortOrder(2);
        plan.setActive(true);

        given(businessRepository.findByIdAndDeletedAtIsNull(BUSINESS_ID)).willReturn(Optional.of(business));
        given(settingsService.isBillingEnabled()).willReturn(true);
        given(settingsService.requireActivePlan("business")).willReturn(plan);
        given(settingsService.planEvenIfInactive("business")).willReturn(plan);
        given(pricingService.resolveRenewalAmount(plan, 1)).willReturn(new BigDecimal("800.00"));
        given(orderRepository.save(any())).willAnswer(invocation -> {
            var order = (zelisline.ub.billing.domain.SubscriptionRenewalOrder) invocation.getArgument(0);
            if (order.getId() == null) {
                order.setId(UUID.randomUUID().toString());
            }
            return order;
        });
    }

    @Test
    void packageRenewalCollectsOnCustodyDarajaRail() {
        given(platformStkRailService.require(anyString())).willReturn(new PlatformStkRailService.Rail(
                GatewayType.DARAJA,
                PlatformDarajaSettings.PLATFORM_DARAJA_CONFIG_ID,
                "Custody rail (DARAJA)",
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

        SubscriptionBillingDtos.RenewSubscriptionResponse response = service.initiate(
                BUSINESS_ID,
                new SubscriptionBillingDtos.RenewSubscriptionRequest("business", 1, "0721530181"));

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.amountKes()).isEqualByComparingTo("800.00");

        ArgumentCaptor<Map<String, String>> credentials = ArgumentCaptor.forClass(Map.class);
        verify(paymentGatewayStkService).initiateWithCredentials(
                eq("DARAJA"),
                eq(PlatformDarajaSettings.PLATFORM_DARAJA_CONFIG_ID),
                eq(BUSINESS_ID),
                credentials.capture(),
                eq("254721530181"),
                eq(new BigDecimal("800.00")),
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
                eq(StkPushContextType.SUBSCRIPTION_RENEWAL),
                anyString(),
                any(),
                eq("254721530181"));
    }

    @Test
    void packageRenewalStopsBeforeChargingWhenNoRailIsConfigured() {
        given(platformStkRailService.require(anyString())).willThrow(new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Platform M-Pesa is not configured. Ask Super Admin to turn on the Custody rail."));

        assertThatThrownBy(() -> service.initiate(
                BUSINESS_ID,
                new SubscriptionBillingDtos.RenewSubscriptionRequest("business", 1, "0721530181")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        verify(orderRepository, never()).save(any());
        verify(paymentGatewayStkService, never()).initiateWithCredentials(
                anyString(), anyString(), anyString(), anyMap(), anyString(), any(), anyString(), anyString());
    }
}
