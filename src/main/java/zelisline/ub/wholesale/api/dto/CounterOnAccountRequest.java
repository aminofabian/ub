package zelisline.ub.wholesale.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Put a pending counter invoice on the account the clerk priced it for. */
public record CounterOnAccountRequest(
        @NotBlank @Size(max = 36) String invoiceId
) {
}
