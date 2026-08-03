package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PostSupplierPaymentRequest(
        @NotBlank String supplierId,
        @NotNull Instant paidAt,
        @NotBlank String paymentMethod,
        @NotNull BigDecimal paymentAmount,
        @NotNull BigDecimal creditApplied,
        String reference,
        String notes,
        /**
         * Invoice allocations. Empty list = pure advance deposit (cash held as supplier credit).
         */
        @NotNull @Valid List<PostSupplierPaymentAllocationLine> allocations,
        /**
         * When {@code false}, records the payment without SMS / portal payment notification.
         * Null or true keeps the default notify-on-pay behaviour.
         */
        Boolean notifySupplier
) {
}
