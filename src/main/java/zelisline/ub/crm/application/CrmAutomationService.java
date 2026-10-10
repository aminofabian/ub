package zelisline.ub.crm.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.api.dto.CrmDtos;
import zelisline.ub.crm.domain.CrmAutomation;
import zelisline.ub.crm.domain.CrmAutomationRun;
import zelisline.ub.crm.domain.CrmAutomationStep;
import zelisline.ub.crm.domain.CrmContact;
import zelisline.ub.crm.domain.CrmConversation;
import zelisline.ub.crm.domain.CrmMessage;
import zelisline.ub.crm.events.CrmConversationUpdatedEvent;
import zelisline.ub.crm.events.CrmMessageCreatedEvent;
import zelisline.ub.crm.repository.CrmAutomationRepository;
import zelisline.ub.crm.repository.CrmAutomationRunRepository;
import zelisline.ub.crm.repository.CrmAutomationStepRepository;
import zelisline.ub.crm.repository.CrmContactRepository;
import zelisline.ub.crm.repository.CrmConversationRepository;

/**
 * No-code automation rules for the WhatsApp inbox (M3): CRUD over {@link CrmAutomation} +
 * {@link CrmAutomationStep}, an append-only run log, and the inbound rules engine.
 *
 * <p>The engine is invoked by {@link InboundIngest} right after an inbound message is persisted.
 * It evaluates every active rule whose trigger matches, then runs that rule's steps top-to-bottom
 * in a single pass. Sends go through {@link CrmSendPort} (enqueued, never blocking) and each
 * firing is recorded as a {@link CrmAutomationRun}. Failures are isolated per rule and never
 * propagate — the Meta webhook must always answer {@code 200}. See
 * {@code docs/scopes/whatsapp-crm/SCOPE.md} §9 (M3).
 */
@Service
@RequiredArgsConstructor
public class CrmAutomationService {

    private static final Logger log = LoggerFactory.getLogger(CrmAutomationService.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final CrmAutomationRepository automationRepository;
    private final CrmAutomationStepRepository stepRepository;
    private final CrmAutomationRunRepository runRepository;
    private final CrmConversationRepository conversationRepository;
    private final CrmContactRepository contactRepository;
    private final CrmSendPort sendPort;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    // ── CRUD ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CrmDtos.AutomationRow> list(String businessId) {
        return automationRepository.findByBusinessIdOrderByCreatedAtDesc(businessId).stream()
                .map(CrmAutomationService::toRow)
                .toList();
    }

    @Transactional(readOnly = true)
    public CrmDtos.AutomationDetail get(String businessId, String id) {
        CrmAutomation automation = require(businessId, id);
        return toDetail(automation);
    }

    @Transactional
    public CrmDtos.AutomationDetail create(String businessId, CrmDtos.UpsertAutomationRequest request) {
        CrmAutomation automation = new CrmAutomation();
        automation.setBusinessId(businessId);
        applyRequest(automation, request);
        automation.setUpdatedAt(Instant.now());
        CrmAutomation saved = automationRepository.save(automation);
        replaceSteps(saved.getId(), request.steps());
        return toDetail(saved);
    }

    @Transactional
    public CrmDtos.AutomationDetail update(
            String businessId, String id, CrmDtos.UpsertAutomationRequest request) {
        CrmAutomation automation = require(businessId, id);
        applyRequest(automation, request);
        automation.setUpdatedAt(Instant.now());
        CrmAutomation saved = automationRepository.save(automation);
        replaceSteps(saved.getId(), request.steps());
        return toDetail(saved);
    }

    @Transactional
    public CrmDtos.AutomationRow setActive(String businessId, String id, boolean active) {
        CrmAutomation automation = require(businessId, id);
        automation.setActive(active);
        automation.setUpdatedAt(Instant.now());
        return toRow(automationRepository.save(automation));
    }

    @Transactional
    public void delete(String businessId, String id) {
        CrmAutomation automation = require(businessId, id);
        stepRepository.deleteByAutomationId(automation.getId());
        automationRepository.delete(automation);
    }

    @Transactional(readOnly = true)
    public CrmDtos.AutomationRunsPage listRuns(String businessId, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Page<CrmAutomationRun> result =
                runRepository.findByBusinessIdOrderByCreatedAtDesc(businessId, PageRequest.of(p, s));
        return new CrmDtos.AutomationRunsPage(
                result.getContent().stream().map(CrmAutomationService::toRunRow).toList(),
                p,
                s,
                result.getTotalElements());
    }

    // ── Engine ──────────────────────────────────────────────────────────────

    /**
     * Evaluate all active inbound rules for a shop and run the ones that match. Best-effort:
     * a failing rule is logged and never propagates to the caller (the webhook).
     *
     * @param firstInbound true when this is the contact's first-ever inbound message
     */
    @Transactional
    public void runInboundTriggers(
            String businessId, String conversationId, String contactId, String text, boolean firstInbound) {
        evaluate(businessId, CrmAutomation.TRIGGER_KEYWORD, conversationId, contactId, text, firstInbound,
                "message.received");
        if (firstInbound) {
            evaluate(businessId, CrmAutomation.TRIGGER_FIRST_INBOUND, conversationId, contactId, text, firstInbound,
                    "first_inbound_message");
        }
    }

    private void evaluate(
            String businessId,
            String triggerType,
            String conversationId,
            String contactId,
            String text,
            boolean firstInbound,
            String triggerEvent) {
        List<CrmAutomation> rules =
                automationRepository.findByBusinessIdAndTriggerTypeAndActiveTrue(businessId, triggerType);
        for (CrmAutomation rule : rules) {
            if (!triggerMatches(rule, text, firstInbound)) {
                continue;
            }
            try {
                execute(businessId, rule, conversationId, contactId, triggerEvent);
            } catch (Exception ex) {
                log.warn("CRM automation {} failed for business {}: {}", rule.getId(), businessId, ex.getMessage());
            }
        }
    }

    private boolean triggerMatches(CrmAutomation rule, String text, boolean firstInbound) {
        return switch (rule.getTriggerType()) {
            case CrmAutomation.TRIGGER_KEYWORD -> keywordMatches(rule.getTriggerConfig(), text);
            case CrmAutomation.TRIGGER_FIRST_INBOUND -> firstInbound;
            default -> false;
        };
    }

    private boolean keywordMatches(String triggerConfig, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        for (String keyword : readKeywords(triggerConfig)) {
            if (!keyword.isBlank() && haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void execute(
            String businessId, CrmAutomation rule, String conversationId, String contactId, String triggerEvent) {
        CrmConversation conversation = conversationRepository
                .findByIdAndBusinessId(conversationId, businessId)
                .orElse(null);
        CrmContact contact = contactId == null ? null : contactRepository.findById(contactId).orElse(null);
        List<CrmAutomationStep> steps = stepRepository.findByAutomationIdOrderByPositionAsc(rule.getId());

        String runId = UUID.randomUUID().toString();
        List<Map<String, Object>> results = new ArrayList<>();
        boolean anyOk = false;
        boolean anyFailed = false;
        String firstError = null;

        for (CrmAutomationStep step : steps) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("position", step.getPosition());
            result.put("type", step.getStepType());
            try {
                applyStep(businessId, step, conversation, contact, runId);
                result.put("status", "ok");
                anyOk = true;
            } catch (Exception ex) {
                result.put("status", "failed");
                result.put("detail", ex.getMessage());
                anyFailed = true;
                if (firstError == null) {
                    firstError = ex.getMessage();
                }
            }
            results.add(result);
        }

        CrmAutomationRun run = new CrmAutomationRun();
        run.setBusinessId(businessId);
        run.setAutomationId(rule.getId());
        run.setConversationId(conversationId);
        run.setContactId(contactId);
        run.setTriggerEvent(triggerEvent);
        run.setStatus(anyFailed ? (anyOk ? CrmAutomationRun.STATUS_PARTIAL : CrmAutomationRun.STATUS_FAILED)
                : CrmAutomationRun.STATUS_SUCCESS);
        run.setStepsJson(writeJson(results));
        run.setErrorMessage(truncate(firstError));
        runRepository.save(run);

        rule.setExecutionCount(rule.getExecutionCount() + 1);
        rule.setLastExecutedAt(Instant.now());
        automationRepository.save(rule);

        if (conversation != null) {
            publishConversationUpdated(conversation);
        }
    }

    private void applyStep(
            String businessId, CrmAutomationStep step, CrmConversation conversation, CrmContact contact, String runId) {
        JsonNode config = readConfig(step.getStepConfig());
        switch (step.getStepType()) {
            case CrmAutomationStep.ACTION_SEND_MESSAGE -> {
                String body = text(config, "body");
                if (body == null || body.isBlank()) {
                    throw new IllegalStateException("SEND_MESSAGE requires a body");
                }
                if (conversation == null || contact == null) {
                    throw new IllegalStateException("SEND_MESSAGE requires a conversation and contact");
                }
                String messageId = sendPort.enqueueText(
                        businessId, conversation.getId(), contact.getPhoneE164(), body,
                        "auto:" + runId + ":" + step.getPosition());
                conversation.setLastMessageAt(Instant.now());
                conversation.setUpdatedAt(Instant.now());
                conversationRepository.save(conversation);
                if (messageId != null) {
                    eventPublisher.publishEvent(new CrmMessageCreatedEvent(
                            businessId, conversation.getId(), messageId, CrmMessage.DIRECTION_OUTBOUND));
                }
            }
            case CrmAutomationStep.ACTION_ADD_TAG -> {
                String tag = text(config, "tag");
                if (tag == null || tag.isBlank()) {
                    throw new IllegalStateException("ADD_TAG requires a tag");
                }
                if (contact == null) {
                    throw new IllegalStateException("ADD_TAG requires a contact");
                }
                addTagToContact(contact, tag.trim());
            }
            case CrmAutomationStep.ACTION_ASSIGN -> {
                if (conversation == null) {
                    throw new IllegalStateException("ASSIGN requires a conversation");
                }
                String userId = text(config, "userId");
                conversation.setAssignedUserId(userId == null || userId.isBlank() ? null : userId.trim());
                conversation.setUpdatedAt(Instant.now());
                conversationRepository.save(conversation);
            }
            case CrmAutomationStep.ACTION_CLOSE_CONVERSATION -> {
                if (conversation == null) {
                    throw new IllegalStateException("CLOSE_CONVERSATION requires a conversation");
                }
                conversation.setStatus(CrmConversation.STATUS_CLOSED);
                conversation.setUpdatedAt(Instant.now());
                conversationRepository.save(conversation);
            }
            default -> throw new IllegalStateException("Unknown step type: " + step.getStepType());
        }
    }

    private void addTagToContact(CrmContact contact, String tag) {
        List<String> tags = new ArrayList<>(readTags(contact));
        if (tags.contains(tag)) {
            return;
        }
        tags.add(tag);
        contact.setTagsJson(writeJson(tags));
        contact.setUpdatedAt(Instant.now());
        contactRepository.save(contact);
    }

    private void publishConversationUpdated(CrmConversation conversation) {
        eventPublisher.publishEvent(new CrmConversationUpdatedEvent(
                conversation.getBusinessId(),
                conversation.getId(),
                conversation.getStatus(),
                conversation.getAssignedUserId(),
                conversation.getUnreadCount()));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private CrmAutomation require(String businessId, String id) {
        return automationRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Automation not found"));
    }

    private void applyRequest(CrmAutomation automation, CrmDtos.UpsertAutomationRequest request) {
        automation.setName(request.name().trim());
        automation.setTriggerType(request.triggerType().trim());
        automation.setTriggerConfig(blankToNull(request.triggerConfigJson()));
        automation.setActive(request.active());
    }

    private void replaceSteps(String automationId, List<CrmDtos.AutomationStepRequest> steps) {
        stepRepository.deleteByAutomationId(automationId);
        int position = 0;
        for (CrmDtos.AutomationStepRequest step : steps) {
            CrmAutomationStep entity = new CrmAutomationStep();
            entity.setAutomationId(automationId);
            entity.setPosition(position++);
            entity.setStepType(step.type().trim());
            entity.setStepConfig(blankToNull(step.configJson()));
            stepRepository.save(entity);
        }
    }

    private CrmDtos.AutomationDetail toDetail(CrmAutomation automation) {
        List<CrmDtos.AutomationStepRow> steps =
                stepRepository.findByAutomationIdOrderByPositionAsc(automation.getId()).stream()
                        .map(s -> new CrmDtos.AutomationStepRow(s.getId(), s.getStepType(), s.getStepConfig()))
                        .toList();
        return new CrmDtos.AutomationDetail(toRow(automation), steps);
    }

    private List<String> readKeywords(String triggerConfig) {
        JsonNode config = readConfig(triggerConfig);
        JsonNode keywords = config.get("keywords");
        List<String> out = new ArrayList<>();
        if (keywords != null && keywords.isArray()) {
            keywords.forEach(node -> {
                if (node.isTextual()) {
                    out.add(node.asText());
                }
            });
        }
        return out;
    }

    private JsonNode readConfig(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("crm automation json serialization failed", ex);
        }
    }

    private static String text(JsonNode config, String field) {
        JsonNode node = config.get(field);
        return node == null || node.isNull() ? null : node.asText(null);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static CrmDtos.AutomationRow toRow(CrmAutomation automation) {
        return new CrmDtos.AutomationRow(
                automation.getId(),
                automation.getName(),
                automation.getTriggerType(),
                automation.getTriggerConfig(),
                automation.isActive(),
                automation.getExecutionCount(),
                automation.getLastExecutedAt(),
                automation.getCreatedAt());
    }

    private static CrmDtos.AutomationRunRow toRunRow(CrmAutomationRun run) {
        return new CrmDtos.AutomationRunRow(
                run.getId(),
                run.getAutomationId(),
                run.getConversationId(),
                run.getTriggerEvent(),
                run.getStatus(),
                run.getStepsJson(),
                run.getErrorMessage(),
                run.getCreatedAt());
    }
}
