package zelisline.ub.messaging.dto;

public record WhatsAppSendRequest(
    String messagingProduct,
    String recipientType,
    String to,
    String type,
    Text text,
    Template template
) {
    public record Text(String body, boolean previewUrl) {}
    public record Template(String name, Language language) {
        public record Language(String code) {}
    }

    // Static factory methods for common use cases
    public static WhatsAppSendRequest text(String to, String body) {
        return new WhatsAppSendRequest(
            "whatsapp",
            "individual",
            to,
            "text",
            new Text(body, false),
            null
        );
    }

    public static WhatsAppSendRequest template(String to, String templateName, String languageCode) {
        return new WhatsAppSendRequest(
            "whatsapp",
            "individual",
            to,
            "template",
            null,
            new Template(templateName, new Template.Language(languageCode))
        );
    }
}
