package zelisline.ub.credits.email.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CustomerEmailAudienceEligibilityTest {

    @Test
    void skipReasonForEmailCoversMissingSyntheticAndInvalid() {
        assertThat(CustomerEmailAudienceService.skipReasonForEmail(null))
                .isEqualTo(CustomerEmailAudienceService.SKIP_MISSING_EMAIL);
        assertThat(CustomerEmailAudienceService.skipReasonForEmail(" "))
                .isEqualTo(CustomerEmailAudienceService.SKIP_MISSING_EMAIL);
        assertThat(CustomerEmailAudienceService.skipReasonForEmail("shopper.0714@phone.invalid"))
                .isEqualTo(CustomerEmailAudienceService.SKIP_SYNTHETIC_EMAIL);
        assertThat(CustomerEmailAudienceService.skipReasonForEmail("not-an-email"))
                .isEqualTo(CustomerEmailAudienceService.SKIP_INVALID_EMAIL);
        assertThat(CustomerEmailAudienceService.skipReasonForEmail("jane@shop.co.ke")).isNull();
    }
}
