package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RequestDomainHelpRequest(
        /**
         * {@code setup_domain}, {@code connect_owned}, {@code shop_online},
         * {@code theme}, {@code functionality}, or {@code other_change}.
         * Shop-change kinds are a flat KES 5,000.
         */
        @NotBlank @Size(max = 32) String kind,
        @NotBlank @Size(max = 32) String phoneNumber,
        @Size(max = 255) String domain,
        @Size(max = 500) String note
) {
}
