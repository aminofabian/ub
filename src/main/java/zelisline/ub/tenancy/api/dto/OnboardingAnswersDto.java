package zelisline.ub.tenancy.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial questionnaire answers persisted under {@code settings.onboarding.answers}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OnboardingAnswersDto(
        @Pattern(regexp = "1|2|3|4|5plus") String branchCount,
        List<@Size(max = 120) String> branchLocalities,
        @Pattern(regexp = StoreTypeCodes.PATTERN) String storeType,
        List<@Pattern(regexp = StoreTypeCodes.PATTERN) String> storeTypes,
        List<@Size(max = 120) String> selectedDepartments,
        @Pattern(regexp = "yes|no") String onlineStore,
        @Size(max = 64) String storeThemeId,
        @Size(max = 64) String landingTemplateId,
        @Size(max = 255) String displayName,
        @Size(max = 32) String primaryColor,
        @Size(max = 32) String accentColor,
        /** How they plan to stock: {@code new}, {@code spreadsheet}, or {@code other_pos}. */
        @Pattern(regexp = "new|spreadsheet|other_pos") String productSource,
        /** Owner shop line (WhatsApp / SMS / till alerts). */
        @Size(max = 32) String ownerPhone,
        /** Food shops only: where food and drink is made (kitchen station names). */
        @Size(max = 8) List<@Size(max = 80) String> foodStations,
        /** Food shops only: the first dishes and the prices the owner typed. */
        @Size(max = 8) List<@Valid FoodMenuItemAnswerDto> foodMenuItems,
        /** Food shops only: the shop also sells packaged products off the shelf. */
        Boolean sellsProducts,
        /**
         * Food shops with table service only: how many tables to set up at the first branch,
         * named "Table 1" onwards. Zero means the owner said none; absent means not asked.
         */
        @Min(0) @Max(60) Integer foodTableCount
) {
}
