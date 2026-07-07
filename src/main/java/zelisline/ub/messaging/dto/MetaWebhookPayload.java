package zelisline.ub.messaging.dto;

import java.util.List;

public record MetaWebhookPayload(
    String object,
    List<MetaWebhookEntry> entry
) {}
