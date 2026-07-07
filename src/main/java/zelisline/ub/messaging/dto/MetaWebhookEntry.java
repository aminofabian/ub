package zelisline.ub.messaging.dto;

import java.util.List;

public record MetaWebhookEntry(
    String id,
    List<MetaWebhookChange> changes
) {}
