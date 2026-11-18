package zelisline.ub.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Super-admin edit of one business type's presentation (ADR 0029). The {@code code} is not editable
 * (it is a fixed enum); only label, hint, section, order and enabled. Image is uploaded separately.
 */
public record UpdateBusinessTypeRequest(
        @NotBlank @Size(max = 120) String label,
        @Size(max = 400) String hint,
        @NotBlank @Pattern(regexp = "food|service|lodging|shop") String section,
        @PositiveOrZero int displayOrder,
        boolean enabled) {}
