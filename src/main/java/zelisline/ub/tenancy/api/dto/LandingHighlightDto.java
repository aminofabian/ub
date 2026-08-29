package zelisline.ub.tenancy.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.Size;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LandingHighlightDto(
        @Size(max = 120) String title,
        @Size(max = 280) String note,
        @Size(max = 2048) String imageUrl
) {
}
