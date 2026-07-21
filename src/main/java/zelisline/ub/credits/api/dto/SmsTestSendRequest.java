package zelisline.ub.credits.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Admin request to send a standalone SMS test (no WhatsApp / RapidAPI). */
public record SmsTestSendRequest(@NotBlank String phone, String message) {
}
