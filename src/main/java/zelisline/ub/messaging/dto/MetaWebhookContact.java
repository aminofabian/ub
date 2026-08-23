package zelisline.ub.messaging.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MetaWebhookContact(
    Profile profile,
    @JsonProperty("wa_id") String waId
) {
    public record Profile(String name) {}
}
