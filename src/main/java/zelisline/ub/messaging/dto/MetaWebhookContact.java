package zelisline.ub.messaging.dto;

public record MetaWebhookContact(
    Profile profile,
    String waId
) {
    public record Profile(String name) {}
}
