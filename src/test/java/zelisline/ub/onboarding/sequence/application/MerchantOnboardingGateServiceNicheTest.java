package zelisline.ub.onboarding.sequence.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class MerchantOnboardingGateServiceNicheTest {

    @Test
    void detectsNicheSpecialtyStoreTypes() {
        assertThat(MerchantOnboardingGateService.isNicheSpecialty(List.of("butchery"))).isTrue();
        assertThat(MerchantOnboardingGateService.isNicheSpecialty(List.of("cosmetics"))).isTrue();
        assertThat(MerchantOnboardingGateService.isNicheSpecialty(List.of("wines-spirits"))).isTrue();
        assertThat(MerchantOnboardingGateService.isNicheSpecialty(List.of("mini-mart"))).isFalse();
        assertThat(MerchantOnboardingGateService.isNicheSpecialty(List.of())).isFalse();
        assertThat(MerchantOnboardingGateService.isNicheSpecialty(null)).isFalse();
    }
}
