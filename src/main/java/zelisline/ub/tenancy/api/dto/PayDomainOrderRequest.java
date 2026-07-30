package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.NotBlank;

public record PayDomainOrderRequest(@NotBlank String phoneNumber) {}
