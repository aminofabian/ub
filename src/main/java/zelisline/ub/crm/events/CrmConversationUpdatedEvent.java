package zelisline.ub.crm.events;

/**
 * Published when a conversation's mutable state changes (send, assign, new inbound).
 * Consumed by {@code crm.realtime.CrmRealtimeBridge} to fan out
 * {@code crm.conversation.updated}.
 */
public record CrmConversationUpdatedEvent(
        String businessId,
        String conversationId,
        String status,
        String assignedUserId,
        int unreadCount
) {
}
