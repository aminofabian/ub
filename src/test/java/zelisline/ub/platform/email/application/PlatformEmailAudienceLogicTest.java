package zelisline.ub.platform.email.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.platform.email.domain.PlatformEmailCampaignRecipient;

class PlatformEmailAudienceLogicTest {

    @Test
    void invitedIsStuckEvenWhenOnboardingCompleted() {
        assertThat(PlatformEmailAudienceService.isStuck(
                UserStatus.INVITED.wire(), Instant.now(), "completed")).isTrue();
    }

    @Test
    void neverLoggedInIsStuckEvenWhenOnboardingCompleted() {
        assertThat(PlatformEmailAudienceService.isStuck(
                UserStatus.ACTIVE.wire(), null, "completed")).isTrue();
    }

    @Test
    void activeOnboardingIsStuck() {
        assertThat(PlatformEmailAudienceService.isStuck(
                UserStatus.ACTIVE.wire(), Instant.now(), "active")).isTrue();
    }

    @Test
    void completedAndLoggedInIsNotStuck() {
        assertThat(PlatformEmailAudienceService.isStuck(
                UserStatus.ACTIVE.wire(), Instant.now(), "completed")).isFalse();
        assertThat(PlatformEmailAudienceService.isStuck(
                UserStatus.ACTIVE.wire(), Instant.now(), "dismissed")).isFalse();
    }

    @Test
    void skipReasons() {
        assertThat(PlatformEmailAudienceService.skipReasonForEmail(null)).isEqualTo("missing_email");
        assertThat(PlatformEmailAudienceService.skipReasonForEmail("shopper.0714@phone.invalid"))
                .isEqualTo("synthetic_email");
        assertThat(PlatformEmailAudienceService.skipReasonForEmail("jane@shop.co.ke")).isNull();
    }

    @Test
    void invitedContinueKindIsVerify() {
        assertThat(PlatformEmailAudienceService.continueKind("invited"))
                .isEqualTo(PlatformEmailCampaignRecipient.KIND_VERIFY);
        assertThat(PlatformEmailAudienceService.continueKind("active"))
                .isEqualTo(PlatformEmailCampaignRecipient.KIND_HUB);
    }
}
