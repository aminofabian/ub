package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record PostSupplierPaymentRequest(
        @NotBlank String supplierId,
        @NotNull Instant paidAt,
        @NotBlank String paymentMethod,
        @NotNull BigDecimal paymentAmount,
        @NotNull BigDecimal creditApplied,
        String reference,
        String notes,
        @NotEmpty @Valid List<PostSupplierPaymentAllocationLine> allocations
) {
}
