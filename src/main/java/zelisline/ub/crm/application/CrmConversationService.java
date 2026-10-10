package zelisline.ub.crm.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.application.KnowledgeBaseService;
import zelisline.ub.ai.application.WhatsAppReplyAiService;
import zelisline.ub.crm.api.dto.CrmDtos;
import zelisline.ub.crm.domain.CrmContact;
import zelisline.ub.crm.domain.CrmConversation;
import zelisline.ub.crm.domain.CrmMessage;
import zelisline.ub.crm.domain.CrmNote;
import zelisline.ub.crm.events.CrmConversationUpdatedEvent;
import zelisline.ub.crm.events.CrmMessageCreatedEvent;
import zelisline.ub.crm.repository.CrmContactRepository;
import zelisline.ub.crm.repository.CrmConversationRepository;
import zelisline.ub.crm.repository.CrmMessageRepository;
import zelisline.ub.crm.repository.CrmNoteRepository;
import zelisline.ub.payments.application.PaymentPromptPort;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Merchant inbox operations: list/read conversations, send replies (via {@link CrmSendPort}),
 * assign, notes, tags, M-Pesa prompts ({@link PaymentPromptPort}), and AI-drafted replies
 * ({@link WhatsAppReplyAiService}). Tenant-scoped by {@code businessId}.
 *
 * <p>See {@code docs/scopes/whatsapp-crm/SCOPE.md} §8.
 */
@Service
@RequiredArgsConstructor
public class CrmConversationService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int AI_HISTORY_TURNS = 20;

    private final CrmConversationRepository conversationRepository;
    private final CrmContactRepository contactRepository;
    private final CrmMessageRepository messageRepository;
    private final CrmNoteRepository noteRepository;
    private final CrmSendPort sendPort;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final PaymentPromptPort paymentPromptPort;
    private final WhatsAppReplyAiService aiReplyService;
    private final KnowledgeBaseService knowledgeBase;
    private final BusinessRepository businessRepository;

    @Transactional(readOnly = true)
    public CrmDtos.ConversationsPage list(
            String businessId, String status, String assigneeId, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Page<CrmConversation> result = conversationRepository.search(
                businessId, blankToNull(status), blankToNull(assigneeId), PageRequest.of(p, s));
        Map<String, CrmContact> contacts = contactsByIds(
                result.getContent().stream().map(CrmConversation::getContactId).toList());
        List<CrmDtos.ConversationRow> items = result.getContent().stream()
                .map(c -> toRow(c, contacts.get(c.getContactId())))
                .toList();
        return new CrmDtos.ConversationsPage(items, p, s, result.getTotalElements());
    }

    @Transactional
    public CrmDtos.ConversationDetail get(String businessId, String conversationId) {
        CrmConversation conversation = requireConversation(businessId, conversationId);
        markRead(conversation);
        CrmContact contact = contactOrNull(conversation.getContactId());
        List<CrmDtos.MessageRow> messages =
                messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                        .map(CrmConversationService::toMessageRow)
                        .toList();
        return new CrmDtos.ConversationDetail(toRow(conversation, contact), messages);
    }

    /** Enqueue a reply; the outbox drain performs the actual send. */
    @Transactional
    public CrmDtos.SendMessageResponse send(
            String businessId, String conversationId, String body, String idempotencyKey) {
        CrmConversation conversation = requireConversation(businessId, conversationId);
        CrmContact contact = requireContact(conversation);
        String messageId =
                sendPort.enqueueText(businessId, conversationId, contact.getPhoneE164(), body, idempotencyKey);

        conversation.setLastMessageAt(Instant.now());
        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);
        publishConversationUpdated(conversation);

        if (messageId == null) {
            return new CrmDtos.SendMessageResponse(false, null);
        }
        CrmMessage message = messageRepository.findById(messageId).orElse(null);
        if (message != null) {
            eventPublisher.publishEvent(new CrmMessageCreatedEvent(
                    businessId, conversationId, message.getId(), CrmMessage.DIRECTION_OUTBOUND));
        }
        return new CrmDtos.SendMessageResponse(true, message == null ? null : toMessageRow(message));
    }

    @Transactional
    public CrmDtos.ConversationRow assign(String businessId, String conversationId, String assigneeUserId) {
        CrmConversation conversation = requireConversation(businessId, conversationId);
        conversation.setAssignedUserId(blankToNull(assigneeUserId));
        conversation.setUpdatedAt(Instant.now());
        CrmConversation saved = conversationRepository.save(conversation);
        publishConversationUpdated(saved);
        return toRow(saved, contactOrNull(saved.getContactId()));
    }

    /** Replace the contact's tags with {@code tags}. */
    @Transactional
    public CrmDtos.ConversationRow setContactTags(
            String businessId, String conversationId, List<String> tags) {
        CrmConversation conversation = requireConversation(businessId, conversationId);
        CrmContact contact = requireContact(conversation);
        List<String> normalised = tags == null
                ? List.of()
                : tags.stream()
                        .map(String::trim)
                        .filter(tag -> !tag.isEmpty())
                        .distinct()
                        .toList();
        contact.setTagsJson(writeTags(normalised));
        contact.setUpdatedAt(Instant.now());
        contactRepository.save(contact);
        publishConversationUpdated(conversation);
        return toRow(conversation, contact);
    }

    /**
     * M5: request an M-Pesa STK prompt for the customer. All money logic lives in
     * {@code payments} ({@link PaymentPromptPort}); this only resolves the phone and relays.
     */
    @Transactional
    public CrmDtos.PaymentPromptResponse requestPayment(
            String businessId, String conversationId, BigDecimal amount) {
        CrmConversation conversation = requireConversation(businessId, conversationId);
        CrmContact contact = requireContact(conversation);
        PaymentPromptPort.PromptResult result = paymentPromptPort.requestStk(
                businessId, contact.getPhoneE164(), amount, conversationId);
        return new CrmDtos.PaymentPromptResponse(result.accepted(), result.reference(), result.detail());
    }

    /**
     * M5: draft a reply with SokoMind from the conversation's recent turns. Returns text for the
     * agent to review; nothing is sent or stored.
     */
    @Transactional(readOnly = true)
    public CrmDtos.AiDraftResponse draftAiReply(String businessId, String conversationId) {
        requireConversation(businessId, conversationId);
        List<CrmMessage> recent = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        List<WhatsAppReplyAiService.Turn> history = recent.stream()
                .skip(Math.max(0, recent.size() - AI_HISTORY_TURNS))
                .map(m -> new WhatsAppReplyAiService.Turn(m.getDirection(), m.getBody()))
                .toList();
        String shopName = businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .map(Business::getName)
                .orElse(null);
        String query = recent.isEmpty() ? "" : recent.get(recent.size() - 1).getBody();
        List<String> knowledge = knowledgeBase.retrieve(businessId, query, 4);
        return new CrmDtos.AiDraftResponse(aiReplyService.draftReply(shopName, history, knowledge));
    }

    @Transactional
    public CrmDtos.NoteRow addNote(String businessId, String conversationId, String body, String authorUserId) {
        requireConversation(businessId, conversationId);
        CrmNote note = new CrmNote();
        note.setBusinessId(businessId);
        note.setConversationId(conversationId);
        note.setAuthorUserId(authorUserId);
        note.setBody(body);
        CrmNote saved = noteRepository.save(note);
        return new CrmDtos.NoteRow(saved.getId(), saved.getAuthorUserId(), saved.getBody(), saved.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<CrmDtos.NoteRow> listNotes(String businessId, String conversationId) {
        requireConversation(businessId, conversationId);
        return noteRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(n -> new CrmDtos.NoteRow(n.getId(), n.getAuthorUserId(), n.getBody(), n.getCreatedAt()))
                .toList();
    }

    private void publishConversationUpdated(CrmConversation conversation) {
        eventPublisher.publishEvent(new CrmConversationUpdatedEvent(
                conversation.getBusinessId(),
                conversation.getId(),
                conversation.getStatus(),
                conversation.getAssignedUserId(),
                conversation.getUnreadCount()));
    }

    private CrmConversation requireConversation(String businessId, String conversationId) {
        return conversationRepository.findByIdAndBusinessId(conversationId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
    }

    private CrmContact requireContact(CrmConversation conversation) {
        CrmContact contact = contactOrNull(conversation.getContactId());
        if (contact == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Conversation has no contact");
        }
        return contact;
    }

    private void markRead(CrmConversation conversation) {
        if (conversation.getUnreadCount() != 0) {
            conversation.setUnreadCount(0);
            conversation.setUpdatedAt(Instant.now());
            conversationRepository.save(conversation);
            publishConversationUpdated(conversation);
        }
    }

    private CrmContact contactOrNull(String contactId) {
        if (contactId == null || contactId.isBlank()) {
            return null;
        }
        return contactRepository.findById(contactId).orElse(null);
    }

    private Map<String, CrmContact> contactsByIds(List<String> ids) {
        List<String> distinct = ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        Map<String, CrmContact> map = new HashMap<>();
        if (!distinct.isEmpty()) {
            contactRepository.findAllById(distinct).forEach(c -> map.put(c.getId(), c));
        }
        return map;
    }

    private List<String> readTags(CrmContact contact) {
        if (contact == null || contact.getTagsJson() == null || contact.getTagsJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(contact.getTagsJson(), new TypeReference<List<String>>() { });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String writeTags(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("crm contact tag serialization failed", e);
        }
    }

    private CrmDtos.ConversationRow toRow(CrmConversation c, CrmContact contact) {
        return new CrmDtos.ConversationRow(
                c.getId(),
                c.getStatus(),
                c.getAssignedUserId(),
                c.getUnreadCount(),
                c.getLastMessageAt(),
                c.getWindowExpiresAt(),
                c.getContactId(),
                contact == null ? null : contact.getName(),
                contact == null ? null : contact.getPhoneE164(),
                readTags(contact));
    }

    private static CrmDtos.MessageRow toMessageRow(CrmMessage m) {
        return new CrmDtos.MessageRow(
                m.getId(),
                m.getDirection(),
                m.getType(),
                m.getBody(),
                m.getStatus(),
                m.getWaMessageId(),
                m.getFailureReason(),
                m.getCreatedAt());
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
