package zelisline.ub.payments.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Super Admin decision on a merchant's request to take Daraja on the public shop. */
public record ReviewDarajaStorefrontRequest(
        @NotBlank String decision
) {
}
