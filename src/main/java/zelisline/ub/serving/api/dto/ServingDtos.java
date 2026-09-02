package zelisline.ub.serving.api.dto;

import java.time.Instant;
import java.util.List;

import zelisline.ub.support.api.dto.SupportMessageDto;

public final class ServingDtos {

    private ServingDtos() {
    }

    public record StaffRow(
            String id,
            String email,
            String name,
            String phone,
            String deskRole,
            boolean active,
            Instant lastLoginAt,
            Instant createdAt,
            int openCount,
            int waitingCount,
            boolean currentUser
    ) {
    }

    public record InviteStaffRequest(
            String name,
            String email,
            String phone,
            String deskRole,
            String password
    ) {
    }

    public record InviteStaffResponse(
            StaffRow staff,
            String temporaryPassword
    ) {
    }

    public record PatchStaffRequest(
            String deskRole,
            Boolean active,
            String name,
            String phone
    ) {
    }

    public record TicketSummary(
            String id,
            int ticketNumber,
            String displayNumber,
            String type,
            String status,
            String priority,
            String category,
            String subject,
            String businessId,
            String businessName,
            String requesterName,
            String requesterEmail,
            String requesterPhone,
            String shopperName,
            String shopperPhone,
            String orderId,
            String assignedTo,
            String assignedToName,
            String conversationId,
            String contactMessageId,
            Instant lastActivityAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record TicketEvent(
            String id,
            String kind,
            String actorId,
            String actorName,
            String payload,
            Instant createdAt
    ) {
    }

    public record TicketNote(
            String id,
            String authorId,
            String authorName,
            String body,
            Instant createdAt
    ) {
    }

    public record TicketDetail(
            TicketSummary ticket,
            List<SupportMessageDto> messages,
            List<TicketNote> notes,
            List<TicketEvent> events
    ) {
    }

    public record CreateTicketRequest(
            String type,
            String subject,
            String category,
            String priority,
            String businessId,
            String shopperName,
            String shopperPhone,
            String orderId,
            String body
    ) {
    }

    public record AssignTicketRequest(
            String assigneeId
    ) {
    }

    public record StatusRequest(
            String status
    ) {
    }

    public record PatchTicketRequest(
            String category,
            String priority
    ) {
    }

    public record TenantCreateTicketRequest(
            String subject,
            String category,
            String body
    ) {
    }

    public record MessageRequest(
            String body
    ) {
    }

    public record NoteRequest(
            String body
    ) {
    }

    public record BoardAgentColumn(
            String id,
            String name,
            String email,
            String deskRole,
            int openCount,
            int waitingCount,
            List<TicketSummary> tickets
    ) {
    }

    public record BoardResponse(
            List<TicketSummary> unassigned,
            List<BoardAgentColumn> agents,
            List<TicketSummary> waiting,
            List<TicketSummary> resolved
    ) {
    }

    public record AssigneeRow(
            String id,
            String name
    ) {
    }
}
