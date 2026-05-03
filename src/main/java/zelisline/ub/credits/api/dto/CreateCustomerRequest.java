package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record CreateCustomerRequest(
        @NotBlank @Size(max = 500) String name,
        @Size(max = 255) String email,
        @Size(max = 10_000) String notes,
        BigDecimal creditLimit,
        @NotEmpty @Valid List<CustomerPhoneDraft> phones
) {
}
