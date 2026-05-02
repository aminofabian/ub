package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Payload for {@code PATCH /api/v1/me}. Self-service surface — the role,
 * status, password, and PIN are deliberately not editable here (PHASE_1_PLAN.md
 * §2.3).
 */
public record UpdateMeRequest(
        @Size(max = 255) String name,
        @Size(max = 50) String phone
) {
}
