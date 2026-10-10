package zelisline.ub.crm.api.dto;

/**
 * Why the merchant inbox may be quiet — surfaced so an empty inbox explains itself instead of
 * showing a bare "No conversations yet".
 *
 * @param inboundEnabled      the channel inbound switch (platform: {@code app.integrations.whatsapp.enabled}
 *                            or the Super Admin console override)
 * @param hasActiveNumber     the shop has an active WhatsApp number routed to it
 * @param whatsappNumber      the routed number's display value, for confirmation
 * @param platformConfigured  the platform Meta app (token + phone id) is configured
 */
public record CrmInboxStatusResponse(
        boolean inboundEnabled,
        boolean hasActiveNumber,
        String whatsappNumber,
        boolean platformConfigured
) {
}
