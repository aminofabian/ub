package zelisline.ub.crm.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** DTOs for the merchant WhatsApp inbox API. */
public final class CrmDtos {

    private CrmDtos() {
    }

    public record ConversationRow(
            String id,
            String status,
            String assignedUserId,
            int unreadCount,
            Instant lastMessageAt,
            Instant windowExpiresAt,
            String contactId,
            String contactName,
            String contactPhone,
            List<String> contactTags
    ) {
    }

    public record MessageRow(
            String id,
            String direction,
            String type,
            String body,
            String status,
            String waMessageId,
            String failureReason,
            Instant createdAt
    ) {
    }

    public record ConversationDetail(
            ConversationRow conversation,
            List<MessageRow> messages
    ) {
    }

    public record ConversationsPage(
            List<ConversationRow> items,
            int page,
            int size,
            long total
    ) {
    }

    public record SendMessageRequest(
            @NotBlank @Size(max = 4096) String body
    ) {
    }

    public record SendMessageResponse(
            boolean queued,
            MessageRow message
    ) {
    }

    /** {@code userId} null/blank unassigns. */
    public record AssignRequest(
            @Size(max = 36) String userId
    ) {
    }

    public record NoteRequest(
            @NotBlank @Size(max = 4096) String body
    ) {
    }

    public record NoteRow(
            String id,
            String authorUserId,
            String body,
            Instant createdAt
    ) {
    }

    public record QuickReplyRow(
            String id,
            String shortcut,
            String body,
            Instant createdAt
    ) {
    }

    public record QuickReplyRequest(
            @NotBlank @Size(max = 64) String shortcut,
            @NotBlank @Size(max = 4096) String body
    ) {
    }

    public record TagRow(
            String id,
            String name,
            String color,
            Instant createdAt
    ) {
    }

    public record CreateTagRequest(
            @NotBlank @Size(max = 64) String name,
            @Size(max = 16) String color
    ) {
    }

    /** Replaces the contact's tags with this list. */
    public record SetContactTagsRequest(
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 64) String> tags
    ) {
    }

    /** WhatsApp "pay now": request an M-Pesa STK prompt for the customer (M5). */
    public record RequestPaymentRequest(
            @NotNull @DecimalMin(value = "1") BigDecimal amount
    ) {
    }

    public record PaymentPromptResponse(
            boolean accepted,
            String reference,
            String detail
    ) {
    }

    /** AI-drafted reply for the agent to review (M5). */
    public record AiDraftResponse(
            String draft
    ) {
    }

    // ── Knowledge base + auto-reply settings (M5) ───────────────────────

    public record KnowledgeDocRow(
            String id,
            String title,
            String content,
            Instant createdAt
    ) {
    }

    public record CreateKnowledgeDocRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 20000) String content
    ) {
    }

    public record AiSettingsResponse(
            boolean autoReplyEnabled,
            int maxRepliesPerConversation,
            String handoffUserId
    ) {
    }

    public record UpdateAiSettingsRequest(
            boolean autoReplyEnabled,
            @Min(0) @Max(20) int maxRepliesPerConversation,
            @Size(max = 36) String handoffUserId
    ) {
    }

    /** Hand-off agent option: an active user who can work the inbox (M5). */
    public record AgentRow(
            String id,
            String name
    ) {
    }

    // ── Automations (M3) ────────────────────────────────────────────────

    public record AutomationStepRow(
            String id,
            String type,
            String configJson
    ) {
    }

    public record AutomationRow(
            String id,
            String name,
            String triggerType,
            String triggerConfigJson,
            boolean active,
            int executionCount,
            Instant lastExecutedAt,
            Instant createdAt
    ) {
    }

    public record AutomationDetail(
            AutomationRow automation,
            List<AutomationStepRow> steps
    ) {
    }

    public record AutomationStepRequest(
            @NotBlank @Size(max = 40) String type,
            @Size(max = 4000) String configJson
    ) {
    }

    public record UpsertAutomationRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 40) String triggerType,
            @Size(max = 4000) String triggerConfigJson,
            boolean active,
            @NotNull @Size(max = 25) List<@Valid AutomationStepRequest> steps
    ) {
    }

    public record SetActiveRequest(
            boolean active
    ) {
    }

    public record AutomationRunRow(
            String id,
            String automationId,
            String conversationId,
            String triggerEvent,
            String status,
            String stepsJson,
            String errorMessage,
            Instant createdAt
    ) {
    }

    public record AutomationRunsPage(
            List<AutomationRunRow> items,
            int page,
            int size,
            long total
    ) {
    }

    // ── Broadcasts (M4) ─────────────────────────────────────────────────

    /** Audience definition. {@code type} is {@code all} or {@code tag}. */
    public record BroadcastAudience(
            @NotBlank @Size(max = 16) String type,
            @Size(max = 64) String tag
    ) {
    }

    public record CreateBroadcastRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 16) String mode,
            @Size(max = 4000) String body,
            @Size(max = 128) String templateName,
            @Size(max = 16) String templateLanguage,
            @NotNull @Valid BroadcastAudience audience
    ) {
    }

    public record BroadcastRow(
            String id,
            String name,
            String mode,
            String status,
            int totalCount,
            Instant createdAt
    ) {
    }

    public record BroadcastsPage(
            List<BroadcastRow> items,
            int page,
            int size,
            long total
    ) {
    }

    public record BroadcastRecipientRow(
            String id,
            String contactId,
            String phoneE164,
            String status,
            String waMessageId,
            String errorMessage,
            Instant createdAt
    ) {
    }

    public record BroadcastDetail(
            BroadcastRow broadcast,
            int pendingCount,
            int sentCount,
            int deliveredCount,
            int readCount,
            int failedCount,
            int skippedCount,
            List<BroadcastRecipientRow> recipients
    ) {
    }
}
