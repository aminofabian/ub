package zelisline.ub.crm.events;

/**
 * Published when a Meta delivery-status callback updates an outbound message. Consumed by
 * {@code crm.realtime.CrmRealtimeBridge} to fan out {@code crm.message.status}.
 */
public record CrmMessageStatusEvent(
        String businessId,
        String conversationId,
        String waMessageId,
        String status
) {
}
