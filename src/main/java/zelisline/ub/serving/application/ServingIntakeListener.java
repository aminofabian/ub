package zelisline.ub.serving.application;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.support.api.dto.SupportMessageDto;
import zelisline.ub.support.application.SupportEvents;
import zelisline.ub.support.domain.SupportConversation;
import zelisline.ub.support.repository.SupportConversationRepository;

@Component
@RequiredArgsConstructor
public class ServingIntakeListener {

    private final SupportConversationRepository conversationRepository;
    private final ServingTicketService servingTicketService;

    @EventListener
    public void onSupportMessage(SupportEvents.SupportMessageSentEvent event) {
        if (event == null || event.conversationId() == null) {
            return;
        }
        SupportConversation conversation = conversationRepository.findById(event.conversationId()).orElse(null);
        if (conversation == null) {
            return;
        }
        SupportMessageDto dto = new SupportMessageDto(
                event.messageId(),
                event.conversationId(),
                event.senderType(),
                event.senderUserId(),
                event.senderName(),
                event.body(),
                event.messageKind(),
                event.orderCard(),
                event.welcomeCard(),
                event.attachment(),
                event.replyTo(),
                null,
                event.createdAt()
        );
        servingTicketService.openFromConversation(conversation, dto);
    }
}
