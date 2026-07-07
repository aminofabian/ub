package zelisline.ub.messaging.dto;

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
    public record Image(String caption, String mimeType, String sha256, String id) {}
    public record Document(String filename, String mimeType, String sha256, String id, String caption) {}
    public record Audio(String mimeType, String sha256, String id, Integer voice) {}
    public record Video(String caption, String mimeType, String sha256, String id) {}
    public record Location(Double latitude, Double longitude, String name, String address) {}
    public record Button(String payload, String text) {}
    public record Interactive(ButtonReply buttonReply, ListReply listReply) {
        public record ButtonReply(String id, String title) {}
        public record ListReply(String id, String title, String description) {}
    }
    public record Order(String catalogId, String text, java.util.List<ProductItem> productItems) {
        public record ProductItem(String productRetailerId, String quantity, Double itemPrice, String currency) {}
    }
    public record Referral(String sourceUrl, String sourceId, String sourceType, String headline, String body, String mediaType) {}
    public record System(String body, String newWaId, String type) {}
    public record Sticker(String mimeType, String sha256, String id, Boolean animated) {}
    public record Reaction(String messageId, String emoji) {}
    public record Error(Integer code, String title, String message, String errorData) {}
}
