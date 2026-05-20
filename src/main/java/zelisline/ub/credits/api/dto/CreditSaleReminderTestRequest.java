package zelisline.ub.credits.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreditSaleReminderTestRequest(@NotBlank String phone) {
}
