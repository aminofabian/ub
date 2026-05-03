package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PostRefundRequest(
        @NotEmpty @Valid List<PostRefundLineRequest> lines,
        @NotEmpty @Valid List<PostRefundPaymentRequest> payments,
        @NotBlank String reason
) {
    public record PostRefundLineRequest(
            @NotBlank String saleItemId,
            @NotNull @Positive BigDecimal quantity
    ) {
    }

    public record PostRefundPaymentRequest(
            @NotBlank String method,
            @NotNull @Positive BigDecimal amount,
            String reference
    ) {
    }
}
