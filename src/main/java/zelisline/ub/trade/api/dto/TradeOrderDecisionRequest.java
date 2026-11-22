package zelisline.ub.trade.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TradeOrderDecisionRequest(@NotBlank @Pattern(regexp = "confirm|decline") String decision) {
}
