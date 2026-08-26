package zelisline.ub.integrations.metacapi.application;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Assembles the CAPI {@code POST /{pixelId}/events} body from a
 * {@link MetaCapiEnqueueRequest}. The Authorization header is applied by
 * {@code MetaCapiGraphClient} at send time and is never stored.
 */
@Component
public class MetaCapiPayloadBuilder {

    private static final int EVENT_SOURCE_URL_MAX = 300;

    private final ObjectMapper objectMapper;

    public MetaCapiPayloadBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String build(MetaCapiEnqueueRequest request, String testEventCode) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("event_name", request.eventName());
        event.put("event_id", request.eventId());
        event.put("event_time", request.eventTime().getEpochSecond());
        String sourceUrl = request.eventSourceUrl();
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            String trimmed = sourceUrl.trim();
            event.put("event_source_url",
                    trimmed.length() > EVENT_SOURCE_URL_MAX
                            ? trimmed.substring(0, EVENT_SOURCE_URL_MAX)
                            : trimmed);
        }
        if (request.actionSource() != null && !request.actionSource().isBlank()) {
            event.put("action_source", request.actionSource().trim());
        }
        if (testEventCode != null && !testEventCode.isBlank()) {
            event.put("test_event_code", testEventCode.trim());
        }
        if (request.userData() != null) {
            event.set("user_data", request.userData());
        }
        if (request.customData() != null) {
            event.set("custom_data", request.customData());
        }
        ObjectNode envelope = objectMapper.createObjectNode();
        ArrayNode data = objectMapper.createArrayNode();
        data.add(event);
        envelope.set("data", data);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to build Meta CAPI payload", e);
        }
    }
}
