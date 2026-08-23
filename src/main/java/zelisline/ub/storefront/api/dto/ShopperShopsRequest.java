package zelisline.ub.storefront.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Phase 4 apex identification: the shops a platform-verified phone has a
 * customer record in. The token comes from the tenant-agnostic
 * {@code identify/verify-code} step — never a bare phone (§13).
 */
public record ShopperShopsRequest(
        @NotBlank @Size(max = 32) String phone,
        @NotBlank @Size(max = 128) String phoneVerificationToken
) {
}
