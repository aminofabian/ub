package zelisline.ub.posdraft.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

public record PutPosDraftLineRequest(
        @NotNull BigDecimal quantity,
        @NotNull BigDecimal unitPrice,
        BigDecimal discountAmount,
        Long expectedVersion
) {
}
