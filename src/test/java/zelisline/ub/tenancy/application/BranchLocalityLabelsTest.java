package zelisline.ub.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import zelisline.ub.tenancy.api.dto.OnboardingAnswersDto;
import zelisline.ub.tenancy.api.dto.OnboardingSettingsResponse;
import zelisline.ub.tenancy.domain.Branch;

class BranchLocalityLabelsTest {

    @Test
    void prefersOnboardingLocalitiesThenBranchAddressThenName() {
        Branch withAddress = new Branch();
        withAddress.setName("HQ branch");
        withAddress.setAddress("Westlands, Nairobi");
        withAddress.setActive(true);

        Branch nameOnly = new Branch();
        nameOnly.setName("Kasarani branch");
        nameOnly.setActive(true);

        OnboardingSettingsResponse onboarding = new OnboardingSettingsResponse(
                "completed",
                5,
                null,
                null,
                null,
                new OnboardingAnswersDto(
                        "2",
                        List.of("Mirema"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        List<String> labels = BranchLocalityLabels.fromOnboardingAndBranches(
                onboarding, List.of(withAddress, nameOnly));

        assertThat(labels).containsExactly("Mirema", "Westlands", "Kasarani");
        assertThat(BranchLocalityLabels.primary(labels)).isEqualTo("Mirema");
    }

    @Test
    void skipsInactiveBranches() {
        Branch inactive = new Branch();
        inactive.setName("Closed branch");
        inactive.setActive(false);

        List<String> labels =
                BranchLocalityLabels.fromOnboardingAndBranches(null, List.of(inactive));

        assertThat(labels).isEmpty();
    }
}
