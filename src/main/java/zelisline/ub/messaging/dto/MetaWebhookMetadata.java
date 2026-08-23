package zelisline.ub.messaging.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MetaWebhookMetadata(
    @JsonProperty("display_phone_number") String displayPhoneNumber,
    @JsonProperty("phone_number_id") String phoneNumberId
) {}
