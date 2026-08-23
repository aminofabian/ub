package zelisline.ub.messaging.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MetaWebhookMessage(
    String id,
    String from,
    String timestamp,
    String type,
    Context context,
    // Type-specific payloads (only one populated based on type)
    Text text,
    Image image,
    Document document,
    Audio audio,
    Video video,
    Location location,
    Button button,
    Interactive interactive,
    Order order,
    Referral referral,
    System system,
    Sticker sticker,
    Reaction reaction,
    Error error
) {
    public record Context(String from, String id) {}
    public record Text(String body) {}
    public record Image(
            @JsonProperty("mime_type") String mimeType,
            String sha256, String id, String caption) {}
    public record Document(
            String filename,
            @JsonProperty("mime_type") String mimeType,
            String sha256, String id, String caption) {}
    public record Audio(
            @JsonProperty("mime_type") String mimeType,
            String sha256, String id, Integer voice) {}
    public record Video(
            String caption,
            @JsonProperty("mime_type") String mimeType,
            String sha256, String id) {}
    public record Location(Double latitude, Double longitude, String name, String address) {}
    public record Button(String payload, String text) {}
    public record Interactive(
            @JsonProperty("button_reply") ButtonReply buttonReply,
            @JsonProperty("list_reply") ListReply listReply) {
        public record ButtonReply(String id, String title) {}
        public record ListReply(String id, String title, String description) {}
    }
    public record Order(String catalogId, String text, java.util.List<ProductItem> productItems) {
        public record ProductItem(String productRetailerId, String quantity, Double itemPrice, String currency) {}
    }
    public record Referral(String sourceUrl, String sourceId, String sourceType, String headline, String body, String mediaType) {}
    public record System(String body, @JsonProperty("new_wa_id") String newWaId, String type) {}
    public record Sticker(
            @JsonProperty("mime_type") String mimeType,
            String sha256, String id, Boolean animated) {}
    public record Reaction(@JsonProperty("message_id") String messageId, String emoji) {}
    public record Error(Integer code, String title, String message, String errorData) {}
}
