package zelisline.ub.crm.events;

/**
 * Published after a CRM message row is created (inbound or outbound). Consumed by
 * {@code crm.realtime.CrmRealtimeBridge} to fan out {@code crm.message.created}.
 */
public record CrmMessageCreatedEvent(
        String businessId,
        String conversationId,
        String messageId,
        String direction
) {
}
