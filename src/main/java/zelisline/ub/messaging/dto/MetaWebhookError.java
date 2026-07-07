package zelisline.ub.messaging.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MetaWebhookError(
    Integer code,
    String title,
    String message,
    @JsonProperty("error_data") ErrorData errorData
) {
    public record ErrorData(String details) {}
}
