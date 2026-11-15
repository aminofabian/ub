package zelisline.ub.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.tenancy.api.dto.FoodMenuItemAnswerDto;
import zelisline.ub.tenancy.api.dto.OnboardingAnswersDto;
import zelisline.ub.tenancy.api.dto.OnboardingPatchRequest;
import zelisline.ub.tenancy.api.dto.OnboardingSettingsResponse;

class BusinessOnboardingSettingsServiceTest {

    private final BusinessOnboardingSettingsService service =
            new BusinessOnboardingSettingsService(new ObjectMapper());

    private static OnboardingAnswersDto foodAnswers() {
        return new OnboardingAnswersDto(
                "1",
                List.of("Mirema"),
                null,
                List.of("restaurant"),
                List.of(),
                "no",
                "store-theme",
                "landing-template",
                "Mirema Kitchen",
                "#0D9488",
                "#5EEAD4",
                null,
                "0712345678",
                List.of("Kitchen", "Bar"),
                List.of(
                        new FoodMenuItemAnswerDto("Chips", new BigDecimal("250.00")),
                        new FoodMenuItemAnswerDto("Soda", new BigDecimal("70"))),
                false,
                12);
    }

    @Test
    void foodSetupAnswersSurviveAMergeAndRead() {
        String settings = service.merge(
                null,
                new OnboardingPatchRequest("active", 3, foodAnswers()));

        OnboardingSettingsResponse read = service.readFromSettingsJson(settings);

        assertThat(read.answers()).isNotNull();
        assertThat(read.answers().foodStations()).containsExactly("Kitchen", "Bar");
        assertThat(read.answers().foodMenuItems()).hasSize(2);
        assertThat(read.answers().foodMenuItems().get(0).name()).isEqualTo("Chips");
        assertThat(read.answers().foodMenuItems().get(0).price())
                .isEqualByComparingTo("250.00");
        assertThat(read.answers().sellsProducts()).isFalse();
        assertThat(read.answers().foodTableCount()).isEqualTo(12);
    }

    @Test
    void aShopWithoutFoodAnswersReadsThemAsNull() {
        OnboardingAnswersDto retail = new OnboardingAnswersDto(
                "1",
                List.of("Mirema"),
                null,
                List.of("mini-mart"),
                List.of("Drinks"),
                "no",
                "store-theme",
                "landing-template",
                "Shop",
                "#121A16",
                "#F4F1EA",
                null,
                null,
                null,
                null,
                null,
                null);

        String settings = service.merge(
                null,
                new OnboardingPatchRequest("active", 3, retail));

        OnboardingSettingsResponse read = service.readFromSettingsJson(settings);

        assertThat(read.answers().foodStations()).isNull();
        assertThat(read.answers().foodMenuItems()).isNull();
        assertThat(read.answers().sellsProducts()).isNull();
        assertThat(read.answers().foodTableCount()).isNull();
    }

    @Test
    void aSavedTableCountThatIsNotAWholeNumberInRangeReadsAsNotAsked() {
        for (String bad : List.of("61", "-1", "2.5", "\"12\"", "true")) {
            String saved = "{\"onboarding\":{\"status\":\"active\",\"step\":3,"
                    + "\"answers\":{\"storeTypes\":[\"restaurant\"],\"foodTableCount\":" + bad + "}}}";

            assertThat(service.readFromSettingsJson(saved).answers().foodTableCount())
                    .as("foodTableCount %s", bad)
                    .isNull();
        }
    }

    @Test
    void zeroTablesIsAnAnswerNotAMissingOne() {
        String saved = "{\"onboarding\":{\"status\":\"active\",\"step\":3,"
                + "\"answers\":{\"storeTypes\":[\"bar\"],\"foodTableCount\":0}}}";

        assertThat(service.readFromSettingsJson(saved).answers().foodTableCount()).isZero();
    }

    @Test
    void anOlderSavedAnswerWithoutFoodFieldsStillReads() {
        String legacy = "{\"onboarding\":{\"status\":\"active\",\"step\":3,"
                + "\"answers\":{\"storeTypes\":[\"restaurant\"],\"selectedDepartments\":[]}}}";

        OnboardingSettingsResponse read = service.readFromSettingsJson(legacy);

        assertThat(read.answers().storeTypes()).containsExactly("restaurant");
        assertThat(read.answers().foodStations()).isNull();
        assertThat(read.answers().foodMenuItems()).isNull();
        assertThat(read.answers().sellsProducts()).isNull();
    }
}
