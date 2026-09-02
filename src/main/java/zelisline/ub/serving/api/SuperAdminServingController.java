package zelisline.ub.serving.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.SuperAdminStaffService;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.serving.api.dto.ServingDtos;
import zelisline.ub.serving.application.CurrentSuperAdmin;
import zelisline.ub.serving.application.ServingTicketService;
import zelisline.ub.support.api.dto.SupportMessageDto;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin/serving")
@RequiredArgsConstructor
@PreAuthorize("hasPermission(null, 'sa.serving.access')")
public class SuperAdminServingController {

    private final SuperAdminRepository superAdminRepository;
    private final SuperAdminStaffService staffService;
    private final ServingTicketService ticketService;
    private final BusinessRepository businessRepository;

    @GetMapping("/staff")
    @PreAuthorize("hasPermission(null, 'sa.staff.manage')")
    public Map<String, Object> staff() {
        SuperAdmin actor = actor();
        List<ServingDtos.StaffRow> rows = staffService.list(actor);
        return Map.of("staff", rows, "total", rows.size());
    }

    @PostMapping("/staff")
    @PreAuthorize("hasPermission(null, 'sa.staff.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public ServingDtos.InviteStaffResponse invite(@RequestBody ServingDtos.InviteStaffRequest body) {
        return staffService.invite(actor(), body);
    }

    @PatchMapping("/staff/{id}")
    @PreAuthorize("hasPermission(null, 'sa.staff.manage')")
    public ServingDtos.StaffRow patchStaff(
            @PathVariable String id,
            @RequestBody ServingDtos.PatchStaffRequest body
    ) {
        return staffService.patch(actor(), id, body);
    }

    @GetMapping("/tickets")
    public Map<String, Object> tickets(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String businessId,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String contactMessageId,
            @RequestParam(required = false) String q
    ) {
        SuperAdmin actor = actor();
        String assigneeKey = assignee;
        if ("me".equalsIgnoreCase(assignee)) {
            assigneeKey = actor.getId();
        }
        List<ServingDtos.TicketSummary> rows = ticketService.list(
                actor, status, type, assigneeKey, businessId, conversationId, contactMessageId, q);
        return Map.of("tickets", rows, "total", rows.size());
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public ServingDtos.TicketSummary create(@RequestBody ServingDtos.CreateTicketRequest body) {
        return ticketService.createManual(actor(), body);
    }

    @GetMapping("/tickets/{id}")
    public ServingDtos.TicketDetail ticket(@PathVariable String id) {
        return ticketService.get(actor(), id);
    }

    @PatchMapping("/tickets/{id}")
    public ServingDtos.TicketSummary patchTicket(
            @PathVariable String id,
            @RequestBody ServingDtos.PatchTicketRequest body
    ) {
        return ticketService.patch(actor(), id, body);
    }

    @PostMapping("/tickets/{id}/assign")
    public ServingDtos.TicketSummary assign(
            @PathVariable String id,
            @RequestBody(required = false) ServingDtos.AssignTicketRequest body
    ) {
        String assigneeId = body == null ? null : body.assigneeId();
        return ticketService.assign(actor(), id, assigneeId);
    }

    @PostMapping("/tickets/{id}/claim")
    public ServingDtos.TicketSummary claim(@PathVariable String id) {
        return ticketService.claim(actor(), id);
    }

    @PostMapping("/tickets/{id}/status")
    public ServingDtos.TicketSummary status(
            @PathVariable String id,
            @RequestBody ServingDtos.StatusRequest body
    ) {
        return ticketService.setStatus(actor(), id, body == null ? null : body.status());
    }

    @PostMapping("/tickets/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportMessageDto reply(
            @PathVariable String id,
            @RequestBody ServingDtos.MessageRequest body
    ) {
        return ticketService.reply(actor(), id, body == null ? null : body.body());
    }

    @PostMapping("/tickets/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public ServingDtos.TicketNote note(
            @PathVariable String id,
            @RequestBody ServingDtos.NoteRequest body
    ) {
        return ticketService.addNote(actor(), id, body == null ? null : body.body());
    }

    @PostMapping("/tickets/{id}/organize")
    public ServingDtos.OrganizeResult organize(@PathVariable String id) {
        return ticketService.organize(actor(), id);
    }

    @PostMapping("/tickets/organize-from-conversation/{conversationId}")
    public ServingDtos.OrganizeResult organizeFromConversation(@PathVariable String conversationId) {
        return ticketService.organizeFromConversation(actor(), conversationId);
    }

    @PostMapping("/tickets/organize-from-contact/{contactMessageId}")
    public ServingDtos.OrganizeResult organizeFromContact(@PathVariable String contactMessageId) {
        return ticketService.organizeFromContact(actor(), contactMessageId);
    }

    @PostMapping("/tickets/{id}/points")
    @ResponseStatus(HttpStatus.CREATED)
    public ServingDtos.TicketPoint addPoint(
            @PathVariable String id,
            @RequestBody ServingDtos.AddPointRequest body
    ) {
        return ticketService.addPoint(actor(), id, body);
    }

    @PostMapping("/tickets/{id}/points/{pointId}/complete")
    public ServingDtos.TicketPoint completePoint(
            @PathVariable String id,
            @PathVariable String pointId
    ) {
        return ticketService.completePoint(actor(), id, pointId);
    }

    @PostMapping("/tickets/{id}/points/{pointId}/reopen")
    public ServingDtos.TicketPoint reopenPoint(
            @PathVariable String id,
            @PathVariable String pointId
    ) {
        return ticketService.reopenPoint(actor(), id, pointId);
    }

    @PostMapping("/tickets/from-conversation/{conversationId}")
    public ServingDtos.TicketSummary promote(@PathVariable String conversationId) {
        return ticketService.promoteConversation(actor(), conversationId);
    }

    @PostMapping("/tickets/from-contact/{contactMessageId}")
    public ServingDtos.TicketSummary fromContact(@PathVariable String contactMessageId) {
        ServingDtos.TicketSummary ticket = ticketService.openFromContact(contactMessageId);
        if (ticket == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Contact message not found");
        }
        return ticket;
    }

    @GetMapping("/board")
    public ServingDtos.BoardResponse board() {
        return ticketService.board(actor());
    }

    @GetMapping("/assignees")
    public Map<String, Object> assignees() {
        List<ServingDtos.AssigneeRow> rows = superAdminRepository.findAll().stream()
                .filter(SuperAdmin::isActive)
                .sorted(java.util.Comparator.comparing(SuperAdmin::getName, String.CASE_INSENSITIVE_ORDER))
                .map(s -> new ServingDtos.AssigneeRow(s.getId(), s.getName()))
                .toList();
        return Map.of("assignees", rows);
    }

    @GetMapping("/shops")
    public Map<String, Object> shops() {
        var rows = businessRepository.findByDeletedAtIsNull().stream()
                .sorted(java.util.Comparator.comparing(zelisline.ub.tenancy.domain.Business::getName,
                        String.CASE_INSENSITIVE_ORDER))
                .map(b -> Map.of("id", b.getId(), "name", b.getName(), "slug", b.getSlug()))
                .toList();
        return Map.of("shops", rows);
    }

    private SuperAdmin actor() {
        return CurrentSuperAdmin.require(superAdminRepository);
    }
}
