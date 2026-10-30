package zelisline.ub.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import zelisline.ub.billing.api.dto.SubscriptionBillingDtos;
import zelisline.ub.billing.domain.PlatformSubscriptionPlan;
import zelisline.ub.billing.repository.SubscriptionRenewalOrderRepository;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.application.PaymentGatewayStkService;
import zelisline.ub.payments.domain.GatewayStkPush;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.StkPushContextType;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Package renewals prompt on the platform Daraja app.
 */
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

    private final SubscriptionRenewalService service = new SubscriptionRenewalService(
            orderRepository,
            settingsService,
            billingService,
            pricingService,
            planFitService,
            accessPolicy,
            businessRepository,
            paymentGatewayStkService,
            gatewayStkPushService);

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
    void packageRenewalUsesPlatformDaraja() {
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

        SubscriptionBillingDtos.RenewSubscriptionResponse response = service.initiate(
                BUSINESS_ID,
                new SubscriptionBillingDtos.RenewSubscriptionRequest("business", 1, "0721530181"));

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.amountKes()).isEqualByComparingTo("800.00");

        verify(paymentGatewayStkService).initiateToPlatformDestination(
                eq(BUSINESS_ID),
                eq("254721530181"),
                eq(new BigDecimal("800.00")),
                anyString(),
                anyString());

        verify(gatewayStkPushService).registerPush(
                eq(BUSINESS_ID),
                eq(GatewayType.DARAJA),
                eq("platform-daraja"),
                eq("ws_CO_1"),
                anyString(),
                eq(StkPushContextType.SUBSCRIPTION_RENEWAL),
                anyString(),
                any(),
                eq("254721530181"));
    }

    @Test
    void packageRenewalRecordsTheGatewayFailure() {
        given(paymentGatewayStkService.initiateToPlatformDestination(
                anyString(), anyString(), any(BigDecimal.class), anyString(), anyString()))
                .willReturn(PaymentGatewayStkService.StkPushOutcome.rejected(
                        null, "NO_GATEWAY", "Online payment is not available right now."));

        SubscriptionBillingDtos.RenewSubscriptionResponse response = service.initiate(
                BUSINESS_ID,
                new SubscriptionBillingDtos.RenewSubscriptionRequest("business", 1, "0721530181"));

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.message()).isEqualTo("Online payment is not available right now.");
        verify(gatewayStkPushService, never()).registerPush(
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), any(), anyString());
    }
}
