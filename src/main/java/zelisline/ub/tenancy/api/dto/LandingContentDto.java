package zelisline.ub.tenancy.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.Size;

/**
 * Optional CMS-lite fields for landing / coming-soon templates.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LandingContentDto(
        @Size(max = 120) String headline,
        @Size(max = 280) String subheadline,
        @Size(max = 40) String phone,
        @Size(max = 40) String whatsapp,
        @Size(max = 200) String hours,
        @Size(max = 200) String address,
        @Size(max = 64) String ctaLabel
) {
}
