package zelisline.ub.onboarding.sequence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.onboarding.sequence.MerchantOnboardingStep;
import zelisline.ub.onboarding.sequence.domain.MerchantOnboardingEnrollment;
import zelisline.ub.onboarding.sequence.domain.MerchantOnboardingSend;
import zelisline.ub.onboarding.sequence.repository.MerchantOnboardingEnrollmentRepository;
import zelisline.ub.onboarding.sequence.repository.MerchantOnboardingSendRepository;
import zelisline.ub.opsalerts.application.BusinessOpsAlertSettingsService;
import zelisline.ub.opsalerts.application.TenantOpsAlertDispatcher;
import zelisline.ub.opsalerts.domain.OpsAlertType;
import zelisline.ub.platform.email.application.PlatformCampaignEmailRenderer;
import zelisline.ub.platform.email.application.PlatformEmailAudienceService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@ExtendWith(MockitoExtension.class)
class MerchantOnboardingSequenceServiceTest {

    @Mock
    private MerchantOnboardingEnrollmentRepository enrollmentRepository;
    @Mock
    private MerchantOnboardingSendRepository sendRepository;
    @Mock
    private MerchantOnboardingGateService gateService;
    @Mock
    private MerchantOnboardingMessageRenderer messageRenderer;
    @Mock
    private MerchantOnboardingMuteToken muteToken;
    @Mock
    private PlatformCampaignEmailRenderer campaignEmailRenderer;
    @Mock
    private BusinessRepository businessRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationService outboundMail;
    @Mock
    private zelisline.ub.notifications.application.NotificationService inAppNotifications;
    @Mock
    private PlatformEmailAudienceService audienceService;
    @Mock
    private TenantOpsAlertDispatcher opsAlertDispatcher;
    @Mock
    private BusinessOpsAlertSettingsService opsAlertSettings;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private MerchantOnboardingSequenceService service;

    /** Behave like the real repo: rows are keyed by (step, channel), any row marks handled. */
    private final Map<String, MerchantOnboardingSend> rows = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(sendRepository.existsByBusinessIdAndStepKeyAndChannel(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> rows.containsKey(key(inv.getArgument(1), inv.getArgument(2))));
        when(sendRepository.findByBusinessIdAndStepKeyAndChannel(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get(key(inv.getArgument(1), inv.getArgument(2)))));
        doAnswer(inv -> {
            MerchantOnboardingSend row = inv.getArgument(0);
            rows.put(key(row.getStepKey(), row.getChannel()), row);
            return row;
        }).when(sendRepository).save(any(MerchantOnboardingSend.class));
    }

    @Test
    void nonNotableWebOrderDoesNotPoisonWhatsAppForLaterNotableOrder() {
        when(enrollmentRepository.findById("biz-1")).thenReturn(Optional.of(enrollment()));
        when(gateService.snapshot("biz-1")).thenReturn(snapshot());
        when(opsAlertSettings.shouldAlert("biz-1", OpsAlertType.ONBOARDING)).thenReturn(true);
        when(messageRenderer.render(any(MerchantOnboardingStep.class), any(), any(), any(), any()))
                .thenReturn(msg());

        // First web order: KES 500 — below the notable threshold. In-app fires, WhatsApp does not.
        service.onFirstWebOrder("biz-1", new BigDecimal("500.00"));
        verify(opsAlertDispatcher, never()).dispatch(any(), any(), any());

        // Second web order: KES 5,000 — notable, so WhatsApp must still be attempted.
        // Regression: the non-notable order used to record a terminal skip that
        // permanently blocked any later WhatsApp for this step.
        service.onFirstWebOrder("biz-1", new BigDecimal("5000.00"));
        verify(opsAlertDispatcher).dispatch(
                "biz-1", OpsAlertType.ONBOARDING, "Fill your shelf in 10 minutes — Kiosk");
    }

    @Test
    void firstEmailFailureRecordsRetryableFailedRow() {
        seedDueEnrollment();
        when(outboundMail.sendPlatformCampaignEmail(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("smtp down"));

        service.processDueBatch();

        assertThat(rows.get("M1:EMAIL")).isNotNull();
        assertThat(rows.get("M1:EMAIL").getStatus()).isEqualTo(MerchantOnboardingSend.STATUS_FAILED);
        assertThat(rows.get("M1:EMAIL").getRetryCount()).isEqualTo(1);
        assertThat(rows.get("M1:EMAIL").getNextRetryAt()).isNotNull();
        verify(outboundMail, times(1)).sendPlatformCampaignEmail(any(), any(), any(), any(), any());
    }

    @Test
    void dueFailedEmailIsRetriedAndMarkedSent() {
        seedDueEnrollment();
        rows.put(key("M1", MerchantOnboardingSend.CHANNEL_EMAIL), failedEmail(1, Instant.now().minus(Duration.ofHours(1))));

        service.processDueBatch();

        assertThat(rows.get("M1:EMAIL").getStatus()).isEqualTo(MerchantOnboardingSend.STATUS_SENT);
        assertThat(rows.get("M1:EMAIL").getRetryCount()).isZero();
        assertThat(rows.get("M1:EMAIL").getNextRetryAt()).isNull();
        verify(outboundMail, times(1)).sendPlatformCampaignEmail(any(), any(), any(), any(), any());
    }

    @Test
    void exhaustedFailedEmailIsNotRetried() {
        seedDueEnrollment();
        rows.put(key("M1", MerchantOnboardingSend.CHANNEL_EMAIL), failedEmail(2, null));

        service.processDueBatch();

        assertThat(rows.get("M1:EMAIL").getStatus()).isEqualTo(MerchantOnboardingSend.STATUS_FAILED);
        verify(outboundMail, never()).sendPlatformCampaignEmail(any(), any(), any(), any(), any());
    }

    private void seedDueEnrollment() {
        MerchantOnboardingEnrollment e = new MerchantOnboardingEnrollment();
        e.setBusinessId("biz-1");
        e.setOwnerUserId("owner-1");
        e.setEnrolledAt(Instant.now().minus(Duration.ofDays(2)));
        when(enrollmentRepository.findByMutedAtIsNullAndCompletedAtIsNull(any(Pageable.class)))
                .thenReturn(List.of(e), List.of());
        when(enrollmentRepository.findById("biz-1")).thenReturn(Optional.of(e));

        Business business = new Business();
        business.setName("Njeri Fresh Mart");
        when(businessRepository.findByIdAndDeletedAtIsNull("biz-1")).thenReturn(Optional.of(business));

        User user = new User();
        user.setName("Jane");
        user.setEmail("jane@example.com");
        when(userRepository.findById("owner-1")).thenReturn(Optional.of(user));

        when(gateService.snapshot("biz-1")).thenReturn(emptyShelfSnapshot());
        when(gateService.m1DueAt(any(), any())).thenReturn(Instant.now().minus(Duration.ofHours(1)));
        when(audienceService.shopOrigin("biz-1")).thenReturn("https://njerifresh.kiosk.ke");
        when(messageRenderer.render(any(MerchantOnboardingStep.class), any(), any(), any(), any()))
                .thenReturn(msg());
        when(messageRenderer.render(any(MerchantOnboardingStep.class), any(), any(), any(), any(), any()))
                .thenReturn(msg());
    }

    private MerchantOnboardingSend failedEmail(int retryCount, Instant nextRetryAt) {
        MerchantOnboardingSend row = new MerchantOnboardingSend();
        row.setBusinessId("biz-1");
        row.setStepKey("M1");
        row.setChannel(MerchantOnboardingSend.CHANNEL_EMAIL);
        row.setStatus(MerchantOnboardingSend.STATUS_FAILED);
        row.setSkipReason("send_failed");
        row.setDedupeKey("M1:" + MerchantOnboardingSend.CHANNEL_EMAIL);
        row.setRetryCount(retryCount);
        row.setNextRetryAt(nextRetryAt);
        return row;
    }

    private static String key(String step, String channel) {
        return step + ":" + channel;
    }

    private MerchantOnboardingEnrollment enrollment() {
        MerchantOnboardingEnrollment e = new MerchantOnboardingEnrollment();
        e.setBusinessId("biz-1");
        e.setOwnerUserId("owner-1");
        return e;
    }

    private MerchantOnboardingGateService.Snapshot emptyShelfSnapshot() {
        return new MerchantOnboardingGateService.Snapshot(
                0, 0, 0, 0, false, false, false, true, "completed", null, false, false, false,
                java.time.ZoneId.of("Africa/Nairobi"));
    }

    private MerchantOnboardingGateService.Snapshot snapshot() {
        return new MerchantOnboardingGateService.Snapshot(
                5, 0, 1, 1, true, true, false, true, "completed", null, false, false, false,
                java.time.ZoneId.of("Africa/Nairobi"));
    }

    private MerchantOnboardingMessageRenderer.RenderedMessage msg() {
        return new MerchantOnboardingMessageRenderer.RenderedMessage(
                "subject", "preview", "body", "<html></html>", "Open Global catalog",
                "/products/catalog", "Fill your shelf", "Start from Global catalog.",
                "Fill your shelf in 10 minutes — Kiosk");
    }
}
