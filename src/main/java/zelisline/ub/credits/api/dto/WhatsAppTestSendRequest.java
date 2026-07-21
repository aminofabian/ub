package zelisline.ub.credits.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Admin request to send a standalone Meta WhatsApp test message (no SMS / RapidAPI).
 * The message is optional; when blank a friendly default is used.
 */
public record WhatsAppTestSendRequest(@NotBlank String phone, String message) {
}
