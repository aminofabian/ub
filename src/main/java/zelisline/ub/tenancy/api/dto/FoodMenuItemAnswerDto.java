package zelisline.ub.tenancy.api.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.Size;

/**
 * A dish typed during shop setup: the name it will carry in the menu and the price the owner
 * typed. Stored inside {@code settings.onboarding.answers}, so it also round-trips on resume.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FoodMenuItemAnswerDto(
        @Size(max = 191) String name,
        BigDecimal price
) {
}
