package zelisline.ub.pharmacy.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/** The medicines whose catalogue-suggested class the caller has checked and accepts. */
public record ConfirmSuggestedClassesRequest(
        @NotEmpty @Size(max = 500) List<@NotBlank @Size(max = 36) String> itemIds
) {
}
