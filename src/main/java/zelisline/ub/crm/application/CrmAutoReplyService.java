package zelisline.ub.crm.application;

import java.time.Instant;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import zelisline.ub.ai.application.KnowledgeBaseService;
import zelisline.ub.ai.application.WhatsAppReplyAiService;
import zelisline.ub.crm.domain.CrmAiSettings;
import zelisline.ub.crm.domain.CrmConversation;
import zelisline.ub.crm.domain.CrmMessage;
import zelisline.ub.crm.events.CrmMessageCreatedEvent;
import zelisline.ub.crm.repository.CrmAiSettingsRepository;
import zelisline.ub.crm.repository.CrmConversationRepository;
import zelisline.ub.crm.repository.CrmMessageRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Auto-replies to an inbound WhatsApp message with SokoMind, grounded in the shop's knowledge
 * base. Per-business opt-in ({@code crm_ai_settings.auto_reply_enabled}); stands down when a human
 * owns the thread, when auto-reply was disabled for the conversation, or when the per-conversation
 * cap is reached. On a handoff signal it disables auto-reply for the thread and routes to the
 * configured agent.
 *
 * <p>M5. See {@code docs/scopes/whatsapp-crm/SCOPE.md} §M5. Callers must swallow failures — the
 * Meta webhook must always answer 200.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrmAutoReplyService {

    private static final int KNOWLEDGE_CHUNKS = 4;
    private static final String HANDOFF_SIGNAL = "HANDOFF";

    private final CrmAiSettingsRepository settingsRepository;
    private final CrmConversationRepository conversationRepository;
    private final CrmMessageRepository messageRepository;
    private final CrmSendPort sendPort;
    private final WhatsAppReplyAiService aiReplyService;
    private final KnowledgeBaseService knowledgeBase;
    private final BusinessRepository businessRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** Called after an inbound message is persisted; does nothing unless auto-reply applies. */
    @Transactional
    public void maybeAutoReply(
            String businessId, CrmConversation conversation, String phoneE164, String inboundText) {
        if (conversation == null || phoneE164 == null || phoneE164.isBlank()) {
            return;
        }
        if (conversation.isAiAutoreplyDisabled() || conversation.getAssignedUserId() != null) {
            return;
        }
        CrmAiSettings settings = settingsRepository.findById(businessId).orElse(null);
        if (settings == null || !settings.isAutoReplyEnabled()) {
            return;
        }
        if (conversation.getAiReplyCount() >= settings.getMaxRepliesPerConversation()) {
            return;
        }

        List<WhatsAppReplyAiService.Turn> history = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversation.getId()).stream()
                .map(m -> new WhatsAppReplyAiService.Turn(m.getDirection(), m.getBody()))
                .toList();
        List<String> knowledge = knowledgeBase.retrieve(businessId, inboundText, KNOWLEDGE_CHUNKS);
        String shopName = businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .map(Business::getName)
                .orElse(null);

        String draft = aiReplyService.draftReply(shopName, history, knowledge);
        if (draft == null || draft.isBlank()) {
            return;
        }

        if (HANDOFF_SIGNAL.equalsIgnoreCase(draft.trim())) {
            handoff(settings, conversation);
            return;
        }

        String messageId = sendPort.enqueueText(businessId, conversation.getId(), phoneE164, draft, null);
        conversation.setAiReplyCount(conversation.getAiReplyCount() + 1);
        conversation.setLastMessageAt(Instant.now());
        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);
        if (messageId != null) {
            eventPublisher.publishEvent(new CrmMessageCreatedEvent(
                    businessId, conversation.getId(), messageId, CrmMessage.DIRECTION_OUTBOUND));
        }
    }

    private void handoff(CrmAiSettings settings, CrmConversation conversation) {
        conversation.setAiAutoreplyDisabled(true);
        if (settings.getHandoffUserId() != null && !settings.getHandoffUserId().isBlank()) {
            conversation.setAssignedUserId(settings.getHandoffUserId());
        }
        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);
        log.info("CRM auto-reply handoff conversation={} business={}",
                conversation.getId(), conversation.getBusinessId());
    }
}
