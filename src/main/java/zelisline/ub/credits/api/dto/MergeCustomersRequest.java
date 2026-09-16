package zelisline.ub.credits.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Fuse duplicate credit customers into one keep record.
 * Absorbed customers are soft-deleted after balances, sales, and phones move.
 */
public record MergeCustomersRequest(
        @NotBlank @Size(max = 36) String keepId,
        @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 36) String> absorbIds
) {
}
