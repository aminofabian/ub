package zelisline.ub.pricing.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PostPriceRuleRequest(
        @NotBlank String name,
        @NotBlank String ruleType,
        @NotNull String paramsJson,
        boolean active
) {
}
