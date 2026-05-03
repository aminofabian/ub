package zelisline.ub.catalog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PostCategoryPriceRuleRequest(
        @NotBlank @Size(max = 36) String ruleId,
        Integer precedence
) {
}
