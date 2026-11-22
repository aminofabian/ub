package zelisline.ub.trade.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendToShopRequest(@NotBlank @Size(max = 36) String buyerBusinessId) {
}
