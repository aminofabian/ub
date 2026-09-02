package zelisline.ub.serving.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.domain.SuperAdminDeskRoles;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.messages.domain.ContactMessage;
import zelisline.ub.messages.domain.ContactMessageScope;
import zelisline.ub.messages.repository.ContactMessageRepository;
import zelisline.ub.serving.api.dto.ServingDtos;
import zelisline.ub.serving.domain.ServingTicket;
import zelisline.ub.serving.domain.ServingTicketCounter;
import zelisline.ub.serving.domain.ServingTicketEvent;
import zelisline.ub.serving.domain.ServingTicketNote;
import zelisline.ub.serving.domain.ServingTicketPoint;
import zelisline.ub.serving.repository.ServingTicketEventRepository;
import zelisline.ub.serving.repository.ServingTicketNoteRepository;
import zelisline.ub.serving.repository.ServingTicketPointRepository;
import zelisline.ub.serving.repository.ServingTicketRepository;
import zelisline.ub.support.api.dto.SendSupportMessageRequest;
import zelisline.ub.support.api.dto.SupportMessageDto;
import zelisline.ub.support.application.SupportService;
import zelisline.ub.support.domain.SupportConversation;
import zelisline.ub.support.domain.SupportMessage;
import zelisline.ub.support.repository.SupportConversationRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class ServingTicketService {

    private static final Set<String> OPEN_WORK = Set.of(
            ServingTicket.STATUS_NEW,
            ServingTicket.STATUS_OPEN,
            ServingTicket.STATUS_WAITING
    );

    private final ServingTicketRepository ticketRepository;
    private final ServingTicketNoteRepository noteRepository;
    private final ServingTicketEventRepository eventRepository;
    private final ServingTicketPointRepository pointRepository;
    private final SuperAdminRepository superAdminRepository;
    private final BusinessRepository businessRepository;
    private final ContactMessageRepository contactMessageRepository;
    private final SupportConversationRepository conversationRepository;
    private final SupportService supportService;
    private final ServingAgendaOrganizer agendaOrganizer;

    public List<ServingDtos.TicketSummary> list(
            SuperAdmin actor,
            String status,
            String type,
            String assignee,
            String businessId,
            String conversationId,
            String contactMessageId,
            String q
    ) {
        List<ServingTicket> rows = ticketRepository.findAllByOrderByUpdatedAtDesc();
        String statusFilter = blankToNull(status);
        String typeFilter = blankToNull(type);
        String assigneeFilter = blankToNull(assignee);
        String businessFilter = blankToNull(businessId);
        String conversationFilter = blankToNull(conversationId);
        String contactFilter = blankToNull(contactMessageId);
        String query = q == null ? null : q.trim().toLowerCase(Locale.ROOT);

        boolean agent = SuperAdminDeskRoles.isAgent(actor.getDeskRole());
        Map<String, Integer> shopSeq = shopSeqById(rows);
        Map<String, int[]> stats = pointStats(rows);
        return rows.stream()
                .filter(t -> !agent || visibleToAgent(t, actor.getId()))
                .filter(t -> statusFilter == null || statusFilter.equalsIgnoreCase(t.getStatus()))
                .filter(t -> typeFilter == null || typeFilter.equalsIgnoreCase(t.getType()))
                .filter(t -> businessFilter == null || businessFilter.equals(t.getBusinessId()))
                .filter(t -> conversationFilter == null || conversationFilter.equals(t.getConversationId()))
                .filter(t -> contactFilter == null || contactFilter.equals(t.getContactMessageId()))
                .filter(t -> matchesAssignee(t, assigneeFilter))
                .filter(t -> query == null || matchesQuery(t, query))
                .map(t -> toSummary(t, shopSeq.get(t.getId()), stats))
                .toList();
    }

    public ServingDtos.TicketDetail get(SuperAdmin actor, String ticketId) {
        ServingTicket ticket = requireTicket(ticketId);
        assertCanView(actor, ticket);
        List<SupportMessageDto> messages = ticket.getConversationId() == null
                ? List.of()
                : supportService.messagesSince(ticket.getConversationId(), ticket.getThreadFrom());
        List<ServingDtos.TicketNote> notes = noteRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId())
                .stream()
                .map(this::toNote)
                .toList();
        List<ServingDtos.TicketEvent> events = eventRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId())
                .stream()
                .map(this::toEvent)
                .toList();
        return toDetail(ticket, messages, notes, events);
    }

    @Transactional
    public ServingDtos.TicketSummary createManual(SuperAdmin actor, ServingDtos.CreateTicketRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ticket body is required");
        }
        String type = normalizeType(request.type());
        String subject = requireSubject(request.subject());
        String businessId = blankToNull(request.businessId());
        if (ServingTicket.TYPE_TENANT.equals(type) && businessId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant tickets need a shop");
        }
        if (businessId != null) {
            requireBusiness(businessId);
        }

        SupportConversation conversation = supportService.createStandaloneConversation(
                ServingTicket.TYPE_TENANT.equals(type)
                        ? SupportConversation.TYPE_TENANT
                        : SupportConversation.TYPE_VISITOR,
                businessId != null ? businessId : SupportConversation.PLATFORM_BUSINESS,
                actor.getId(),
                actor.getName(),
                subject,
                ServingTicket.TYPE_SHOPPER.equals(type) ? ("shopper-" + Instant.now().toEpochMilli()) : null,
                blankToNull(request.shopperName()),
                blankToNull(request.shopperPhone())
        );

        ServingTicket ticket = newTicket(
                type,
                subject,
                normalizeCategory(request.category()),
                normalizePriority(request.priority()),
                businessId,
                ServingTicket.CREATED_SUPER_ADMIN,
                actor.getId(),
                actor.getName()
        );
        ticket.setShopperName(blankToNull(request.shopperName()));
        ticket.setShopperPhone(blankToNull(request.shopperPhone()));
        ticket.setOrderId(blankToNull(request.orderId()));
        ticket.setConversationId(conversation.getId());
        ticket.setThreadFrom(conversation.getCreatedAt());
        ticketRepository.save(ticket);
        recordEvent(ticket, actor.getId(), actor.getName(), ServingTicketEvent.KIND_CREATED, ticket.displayNumber());

        String body = blankToNull(request.body());
        if (body != null) {
            supportService.postIntakeMessage(
                    conversation.getId(),
                    SupportMessage.SENDER_SUPER_ADMIN,
                    actor.getId(),
                    actor.getName(),
                    body
            );
        }
        return toSummary(ticket);
    }

    @Transactional
    public ServingTicket openFromConversation(SupportConversation conversation, SupportMessageDto message) {
        if (conversation == null) {
            return null;
        }
        if (SupportConversation.TYPE_STOREFRONT.equals(conversation.getConversationType())) {
            return null;
        }
        ServingTicket existing = latestOpenForConversation(conversation.getId());
        if (existing != null) {
            boolean customerReply = message != null
                    && !SupportMessage.SENDER_SUPER_ADMIN.equals(message.senderType());
            if (customerReply && (ServingTicket.STATUS_WAITING.equals(existing.getStatus())
                    || ServingTicket.STATUS_RESOLVED.equals(existing.getStatus()))) {
                applyStatus(existing, ServingTicket.STATUS_OPEN, null, "Customer replied");
            }
            existing.setUpdatedAt(Instant.now());
            return ticketRepository.save(existing);
        }
        if (message != null && SupportMessage.KIND_WELCOME_CARD.equals(message.messageKind())) {
            return null;
        }
        if (message != null && SupportService.PLATFORM_BOT_USER_ID.equals(message.senderUserId())) {
            return null;
        }
        if (message != null && SupportMessage.SENDER_SUPER_ADMIN.equals(message.senderType())) {
            return null;
        }

        boolean shopperShaped = SupportConversation.TYPE_VISITOR.equals(conversation.getConversationType());
        String type = shopperShaped ? ServingTicket.TYPE_SHOPPER : ServingTicket.TYPE_TENANT;
        String subject = firstSubject(conversation, message);
        String businessId = SupportConversation.PLATFORM_BUSINESS.equals(conversation.getBusinessId())
                ? null
                : conversation.getBusinessId();

        ServingTicket ticket = newTicket(
                type,
                subject,
                ServingTicket.CATEGORY_OTHER,
                ServingTicket.PRIORITY_NORMAL,
                businessId,
                shopperShaped ? ServingTicket.CREATED_GUEST : ServingTicket.CREATED_TENANT,
                message == null ? conversation.getCreatedBy() : message.senderUserId(),
                message == null ? conversation.getCreatedByName() : message.senderName()
        );
        ticket.setRequesterName(conversation.getCreatedByName());
        ticket.setShopperGuestId(conversation.getGuestId());
        ticket.setShopperName(conversation.getGuestName());
        ticket.setShopperPhone(conversation.getGuestPhone());
        ticket.setConversationId(conversation.getId());
        ticket.setThreadFrom(conversation.getCreatedAt());
        ticketRepository.save(ticket);
        recordEvent(ticket, null, "Intake", ServingTicketEvent.KIND_CREATED, "Opened from live chat");
        return ticket;
    }

    @Transactional
    public ServingDtos.TicketSummary promoteConversation(SuperAdmin actor, String conversationId) {
        SupportConversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (SupportConversation.TYPE_STOREFRONT.equals(conversation.getConversationType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Storefront chats are escalated by the shop, not promoted here");
        }
        ServingTicket existing = latestOpenForConversation(conversation.getId());
        if (existing != null) {
            return toSummary(existing);
        }
        ServingTicket ticket = openFromConversation(conversation, null);
        if (ticket == null) {
            boolean shopperShaped = SupportConversation.TYPE_VISITOR.equals(conversation.getConversationType());
            ticket = newTicket(
                    shopperShaped ? ServingTicket.TYPE_SHOPPER : ServingTicket.TYPE_TENANT,
                    firstSubject(conversation, null),
                    ServingTicket.CATEGORY_OTHER,
                    ServingTicket.PRIORITY_NORMAL,
                    SupportConversation.PLATFORM_BUSINESS.equals(conversation.getBusinessId())
                            ? null : conversation.getBusinessId(),
                    ServingTicket.CREATED_SUPER_ADMIN,
                    actor.getId(),
                    actor.getName()
            );
            ticket.setConversationId(conversation.getId());
            ticket.setThreadFrom(conversation.getCreatedAt());
            ticket.setShopperGuestId(conversation.getGuestId());
            ticket.setShopperName(conversation.getGuestName());
            ticket.setShopperPhone(conversation.getGuestPhone());
            ticketRepository.save(ticket);
        }
        recordEvent(ticket, actor.getId(), actor.getName(), ServingTicketEvent.KIND_PROMOTED, ticket.displayNumber());
        return toSummary(ticket);
    }

    @Transactional
    public ServingDtos.TicketSummary openFromContact(String contactMessageId) {
        ContactMessage message = contactMessageRepository.findById(contactMessageId)
                .orElse(null);
        if (message == null || message.getScope() != ContactMessageScope.PLATFORM) {
            return null;
        }
        return openFromContact(message);
    }

    @Transactional
    public ServingDtos.TicketSummary openFromContact(ContactMessage message) {
        if (message == null || message.getScope() != ContactMessageScope.PLATFORM) {
            return null;
        }
        ServingTicket existing = ticketRepository.findByContactMessageId(message.getId()).orElse(null);
        if (existing != null) {
            return toSummary(existing);
        }
        SupportConversation conversation = supportService.createStandaloneConversation(
                SupportConversation.TYPE_VISITOR,
                SupportConversation.PLATFORM_BUSINESS,
                SupportService.PLATFORM_BOT_USER_ID,
                message.getName(),
                trimSubject(message.getBody()),
                "contact-" + message.getId(),
                message.getName(),
                message.getPhone()
        );
        ServingTicket ticket = newTicket(
                ServingTicket.TYPE_SHOPPER,
                trimSubject(message.getBody()),
                ServingTicket.CATEGORY_OTHER,
                ServingTicket.PRIORITY_NORMAL,
                null,
                ServingTicket.CREATED_GUEST,
                null,
                message.getName()
        );
        ticket.setRequesterName(message.getName());
        ticket.setRequesterEmail(message.getEmail());
        ticket.setRequesterPhone(message.getPhone());
        ticket.setShopperName(message.getName());
        ticket.setShopperPhone(message.getPhone());
        ticket.setConversationId(conversation.getId());
        ticket.setContactMessageId(message.getId());
        ticket.setThreadFrom(conversation.getCreatedAt());
        ticketRepository.save(ticket);
        recordEvent(ticket, null, message.getName(), ServingTicketEvent.KIND_CREATED, "Talk to Us");
        supportService.postIntakeMessage(
                conversation.getId(),
                SupportMessage.SENDER_GUEST,
                conversation.getGuestId(),
                message.getName(),
                message.getBody()
        );
        return toSummary(ticket);
    }

    @Transactional
    public ServingDtos.TicketSummary escalateStorefront(
            String businessId,
            String conversationId,
            String actorUserId,
            String actorName
    ) {
        SupportConversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (!businessId.equals(conversation.getBusinessId())
                || !SupportConversation.TYPE_STOREFRONT.equals(conversation.getConversationType())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        ServingTicket existing = latestOpenForConversation(conversation.getId());
        if (existing != null) {
            return toSummary(existing);
        }
        ServingTicket ticket = newTicket(
                ServingTicket.TYPE_SHOPPER,
                firstSubject(conversation, null),
                ServingTicket.CATEGORY_MARKETPLACE,
                ServingTicket.PRIORITY_HIGH,
                businessId,
                ServingTicket.CREATED_TENANT,
                actorUserId,
                actorName
        );
        ticket.setShopperGuestId(conversation.getGuestId());
        ticket.setShopperName(conversation.getGuestName());
        ticket.setShopperPhone(conversation.getGuestPhone());
        ticket.setConversationId(conversation.getId());
        ticket.setThreadFrom(conversation.getCreatedAt());
        ticket.setRequesterName(actorName);
        ticket.setOrderId(latestOrderId(conversation.getId()));
        ticketRepository.save(ticket);
        recordEvent(ticket, actorUserId, actorName, ServingTicketEvent.KIND_ESCALATED, "Shop escalated shopper chat");
        return toSummary(ticket);
    }

    public List<ServingDtos.TicketSummary> listForTenant(String businessId, String status) {
        String statusFilter = blankToNull(status);
        List<ServingTicket> mine = ticketRepository.findByBusinessIdOrderByCreatedAtAsc(businessId).stream()
                .filter(t -> ServingTicket.TYPE_TENANT.equals(t.getType())
                        || (ServingTicket.TYPE_SHOPPER.equals(t.getType()) && t.getConversationId() != null))
                .toList();
        Map<String, Integer> shopSeq = shopSeqById(mine);
        Map<String, int[]> stats = pointStats(mine);
        return mine.stream()
                .filter(t -> statusFilter == null || statusFilter.equalsIgnoreCase(t.getStatus()))
                .map(t -> toSummary(t, shopSeq.get(t.getId()), stats))
                .toList();
    }

    public ServingDtos.TicketDetail getForTenant(String businessId, String ticketId) {
        ServingTicket ticket = requireTicket(ticketId);
        if (!businessId.equals(ticket.getBusinessId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found");
        }
        List<SupportMessageDto> messages = ticket.getConversationId() == null
                ? List.of()
                : supportService.messagesSince(ticket.getConversationId(), ticket.getThreadFrom());
        return toDetail(ticket, messages, List.of(), List.of());
    }

    @Transactional
    public ServingDtos.TicketSummary createForTenant(
            String businessId,
            String userId,
            String userName,
            ServingDtos.TenantCreateTicketRequest request
    ) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ticket body is required");
        }
        String subject = requireSubject(request.subject());
        requireBusiness(businessId);
        SupportConversation conversation = supportService.createStandaloneConversation(
                SupportConversation.TYPE_TENANT,
                businessId,
                userId,
                userName,
                subject,
                null,
                userName,
                null
        );
        ServingTicket ticket = newTicket(
                ServingTicket.TYPE_TENANT,
                subject,
                normalizeCategory(request.category()),
                ServingTicket.PRIORITY_NORMAL,
                businessId,
                ServingTicket.CREATED_TENANT,
                userId,
                userName
        );
        ticket.setConversationId(conversation.getId());
        ticket.setThreadFrom(conversation.getCreatedAt());
        ticketRepository.save(ticket);
        recordEvent(ticket, userId, userName, ServingTicketEvent.KIND_CREATED, ticket.displayNumber());
        String body = blankToNull(request.body());
        if (body != null) {
            supportService.postIntakeMessage(
                    conversation.getId(),
                    SupportMessage.SENDER_TENANT,
                    userId,
                    userName,
                    body
            );
        }
        return toSummary(ticket);
    }

    @Transactional
    public SupportMessageDto replyAsTenant(
            String businessId,
            String ticketId,
            String userId,
            String userName,
            String body
    ) {
        ServingTicket ticket = requireTicket(ticketId);
        if (!businessId.equals(ticket.getBusinessId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found");
        }
        if (ticket.getConversationId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ticket has no thread");
        }
        SupportConversation conversation = conversationRepository.findById(ticket.getConversationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        SupportMessageDto sent;
        if (SupportConversation.TYPE_STOREFRONT.equals(conversation.getConversationType())) {
            sent = supportService.sendStorefrontStaffMessage(
                    conversation.getId(),
                    businessId,
                    userId,
                    new SendSupportMessageRequest(body, null, null, null)
            );
        } else if (conversation.getTenantThreadKey() != null) {
            sent = supportService.sendTenantMessage(
                    businessId,
                    userId,
                    new SendSupportMessageRequest(body, null, null, null)
            );
        } else {
            sent = supportService.postIntakeMessage(
                    conversation.getId(),
                    SupportMessage.SENDER_TENANT,
                    userId,
                    userName,
                    body
            );
        }
        if (ServingTicket.STATUS_WAITING.equals(ticket.getStatus())
                || ServingTicket.STATUS_RESOLVED.equals(ticket.getStatus())) {
            applyStatus(ticket, ServingTicket.STATUS_OPEN, null, "Tenant replied");
        }
        recordEvent(ticket, userId, userName, ServingTicketEvent.KIND_MESSAGE, "Tenant reply");
        return sent;
    }

    @Transactional
    public ServingDtos.TicketSummary assign(SuperAdmin actor, String ticketId, String assigneeId) {
        ServingTicket ticket = requireTicket(ticketId);
        assertCanAssign(actor, ticket);
        String next = blankToNull(assigneeId);
        if (next == null) {
            ticket.setAssignedTo(null);
            ticket.setAssignedAt(null);
            if (ServingTicket.STATUS_OPEN.equals(ticket.getStatus())) {
                ticket.setStatus(ServingTicket.STATUS_NEW);
            }
            ticketRepository.save(ticket);
            recordEvent(ticket, actor.getId(), actor.getName(), ServingTicketEvent.KIND_ASSIGNED, "Unassigned");
            return toSummary(ticket);
        }
        SuperAdmin assignee = requireActiveStaff(next);
        ticket.setAssignedTo(assignee.getId());
        ticket.setAssignedAt(Instant.now());
        if (ServingTicket.STATUS_NEW.equals(ticket.getStatus())) {
            ticket.setStatus(ServingTicket.STATUS_OPEN);
        }
        ticketRepository.save(ticket);
        recordEvent(ticket, actor.getId(), actor.getName(), ServingTicketEvent.KIND_ASSIGNED, assignee.getName());
        return toSummary(ticket);
    }

    @Transactional
    public ServingDtos.TicketSummary claim(SuperAdmin actor, String ticketId) {
        ServingTicket ticket = requireTicket(ticketId);
        if (ticket.getAssignedTo() != null) {
            if (actor.getId().equals(ticket.getAssignedTo())) {
                return toSummary(ticket);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ticket is already assigned");
        }
        ticket.setAssignedTo(actor.getId());
        ticket.setAssignedAt(Instant.now());
        if (ServingTicket.STATUS_NEW.equals(ticket.getStatus())) {
            ticket.setStatus(ServingTicket.STATUS_OPEN);
        }
        ticketRepository.save(ticket);
        recordEvent(ticket, actor.getId(), actor.getName(), ServingTicketEvent.KIND_CLAIMED, actor.getName());
        return toSummary(ticket);
    }

    @Transactional
    public ServingDtos.TicketSummary setStatus(SuperAdmin actor, String ticketId, String status) {
        ServingTicket ticket = requireTicket(ticketId);
        assertCanWork(actor, ticket);
        applyStatus(ticket, normalizeStatus(status), actor, null);
        return toSummary(ticket);
    }

    @Transactional
    public ServingDtos.TicketSummary patch(SuperAdmin actor, String ticketId, ServingDtos.PatchTicketRequest request) {
        ServingTicket ticket = requireTicket(ticketId);
        assertCanWork(actor, ticket);
        if (request == null) {
            return toSummary(ticket);
        }
        if (request.category() != null) {
            ticket.setCategory(normalizeCategory(request.category()));
        }
        if (request.priority() != null) {
            ticket.setPriority(normalizePriority(request.priority()));
        }
        ticketRepository.save(ticket);
        recordEvent(ticket, actor.getId(), actor.getName(), ServingTicketEvent.KIND_PRIORITY, ticket.getCategory() + " / " + ticket.getPriority());
        return toSummary(ticket);
    }

    @Transactional
    public SupportMessageDto reply(SuperAdmin actor, String ticketId, String body) {
        ServingTicket ticket = requireTicket(ticketId);
        assertCanWork(actor, ticket);
        if (ticket.getAssignedTo() == null) {
            ticket.setAssignedTo(actor.getId());
            ticket.setAssignedAt(Instant.now());
        }
        if (ServingTicket.STATUS_NEW.equals(ticket.getStatus())
                || ServingTicket.STATUS_WAITING.equals(ticket.getStatus())
                || ServingTicket.STATUS_RESOLVED.equals(ticket.getStatus())) {
            ticket.setStatus(ServingTicket.STATUS_OPEN);
            ticket.setResolvedAt(null);
            ticket.setClosedAt(null);
        }
        ticketRepository.save(ticket);
        if (ticket.getConversationId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ticket has no thread");
        }
        SupportMessageDto sent = supportService.sendServingStaffMessage(
                ticket.getConversationId(),
                actor.getId(),
                actor.getName(),
                new SendSupportMessageRequest(body, null, null, null)
        );
        recordEvent(ticket, actor.getId(), actor.getName(), ServingTicketEvent.KIND_MESSAGE, "Staff reply");
        return sent;
    }

    @Transactional
    public ServingDtos.TicketNote addNote(SuperAdmin actor, String ticketId, String body) {
        ServingTicket ticket = requireTicket(ticketId);
        assertCanView(actor, ticket);
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Note cannot be blank");
        }
        if (text.length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Note is too long");
        }
        ServingTicketNote note = new ServingTicketNote();
        note.setTicketId(ticket.getId());
        note.setAuthorId(actor.getId());
        note.setAuthorName(actor.getName());
        note.setBody(text);
        noteRepository.save(note);
        recordEvent(ticket, actor.getId(), actor.getName(), ServingTicketEvent.KIND_NOTE, "Internal note");
        return toNote(note);
    }

    @Transactional
    public ServingDtos.OrganizeResult organize(SuperAdmin actor, String ticketId) {
        ServingTicket ticket = requireTicket(ticketId);
        assertCanWork(actor, ticket);
        if (ServingTicket.STATUS_CLOSED.equals(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Closed tickets cannot be reorganized");
        }
        List<SupportMessageDto> messages = ticket.getConversationId() == null
                ? List.of()
                : supportService.messagesSince(ticket.getConversationId(), ticket.getThreadFrom());
        String extra = null;
        if (ticket.getContactMessageId() != null) {
            extra = contactMessageRepository.findById(ticket.getContactMessageId())
                    .map(ContactMessage::getBody)
                    .orElse(null);
        }
        ServingAgendaOrganizer.Extraction extraction = agendaOrganizer.extract(
                messages.isEmpty() ? ticket.getSubject() : null,
                messages,
                extra
        );
        applyDrafts(ticket, extraction);
        boolean first = eventRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId()).stream()
                .noneMatch(e -> ServingTicketEvent.KIND_ORGANIZED.equals(e.getKind()));
        recordEvent(
                ticket,
                actor.getId(),
                actor.getName(),
                ServingTicketEvent.KIND_ORGANIZED,
                extraction.points().size() + " points via " + extraction.source()
        );
        if (first && ticket.getConversationId() != null && !extraction.points().isEmpty()) {
            supportService.sendServingStaffMessage(
                    ticket.getConversationId(),
                    actor.getId(),
                    actor.getName(),
                    new SendSupportMessageRequest(formatListMessage(extraction.points()), null, null, null)
            );
        }
        if (ServingTicket.STATUS_NEW.equals(ticket.getStatus())
                || ServingTicket.STATUS_WAITING.equals(ticket.getStatus())
                || ServingTicket.STATUS_RESOLVED.equals(ticket.getStatus())) {
            ticket.setStatus(ServingTicket.STATUS_OPEN);
            ticket.setResolvedAt(null);
            ticket.setClosedAt(null);
        }
        if (ticket.getAssignedTo() == null) {
            ticket.setAssignedTo(actor.getId());
            ticket.setAssignedAt(Instant.now());
        }
        ticketRepository.save(ticket);
        return new ServingDtos.OrganizeResult(get(actor, ticket.getId()), extraction.source());
    }

    @Transactional
    public ServingDtos.OrganizeResult organizeFromConversation(SuperAdmin actor, String conversationId) {
        ServingDtos.TicketSummary promoted = promoteConversation(actor, conversationId);
        return organize(actor, promoted.id());
    }

    @Transactional
    public ServingDtos.OrganizeResult organizeFromContact(SuperAdmin actor, String contactMessageId) {
        ServingDtos.TicketSummary opened = openFromContact(contactMessageId);
        if (opened == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact message not found");
        }
        return organize(actor, opened.id());
    }

    @Transactional
    public ServingDtos.TicketPoint addPoint(SuperAdmin actor, String ticketId, ServingDtos.AddPointRequest request) {
        ServingTicket ticket = requireTicket(ticketId);
        assertCanWork(actor, ticket);
        if (ServingTicket.STATUS_CLOSED.equals(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Closed tickets cannot take new points");
        }
        String title = request == null ? "" : (request.title() == null ? "" : request.title().trim());
        if (title.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Point title is required");
        }
        if (title.length() > 191) {
            title = title.substring(0, 191);
        }
        String detail = request.detail() == null ? null : request.detail().trim();
        if (detail != null && detail.length() > 800) {
            detail = detail.substring(0, 800);
        }
        int seq = pointRepository.findByTicketIdOrderBySeqAsc(ticket.getId()).size() + 1;
        ServingTicketPoint point = new ServingTicketPoint();
        point.setTicketId(ticket.getId());
        point.setSeq(seq);
        point.setTitle(title);
        point.setDetail(detail);
        point.setStatus(ServingTicketPoint.STATUS_OPEN);
        point.setSource(ServingTicketPoint.SOURCE_STAFF);
        pointRepository.save(point);
        recordEvent(ticket, actor.getId(), actor.getName(), ServingTicketEvent.KIND_POINT, seq + ". " + title);
        return toPoint(point);
    }

    @Transactional
    public ServingDtos.TicketPoint completePoint(SuperAdmin actor, String ticketId, String pointId) {
        ServingTicket ticket = requireTicket(ticketId);
        assertCanWork(actor, ticket);
        return markPoint(ticket, pointId, true, actor.getId(), actor.getName(), ServingTicketPoint.COMPLETED_STAFF);
    }

    @Transactional
    public ServingDtos.TicketPoint reopenPoint(SuperAdmin actor, String ticketId, String pointId) {
        ServingTicket ticket = requireTicket(ticketId);
        assertCanWork(actor, ticket);
        return markPoint(ticket, pointId, false, actor.getId(), actor.getName(), ServingTicketPoint.COMPLETED_STAFF);
    }

    @Transactional
    public ServingDtos.TicketPoint completePointAsTenant(
            String businessId,
            String ticketId,
            String pointId,
            String userId,
            String userName
    ) {
        ServingTicket ticket = requireTicket(ticketId);
        if (!businessId.equals(ticket.getBusinessId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found");
        }
        return markPoint(ticket, pointId, true, userId, userName, ServingTicketPoint.COMPLETED_TENANT);
    }

    public ServingDtos.BoardResponse board(SuperAdmin actor) {
        List<ServingTicket> rows = ticketRepository.findAllByOrderByUpdatedAtDesc();
        boolean agent = SuperAdminDeskRoles.isAgent(actor.getDeskRole());
        if (agent) {
            rows = rows.stream().filter(t -> visibleToAgent(t, actor.getId())).toList();
        }
        Map<String, Integer> shopSeq = shopSeqById(rows);
        Map<String, int[]> stats = pointStats(rows);

        List<ServingDtos.TicketSummary> unassigned = rows.stream()
                .filter(t -> t.getAssignedTo() == null && OPEN_WORK.contains(t.getStatus()))
                .map(t -> toSummary(t, shopSeq.get(t.getId()), stats))
                .toList();
        List<ServingDtos.TicketSummary> waiting = rows.stream()
                .filter(t -> ServingTicket.STATUS_WAITING.equals(t.getStatus()))
                .map(t -> toSummary(t, shopSeq.get(t.getId()), stats))
                .toList();
        List<ServingDtos.TicketSummary> resolved = rows.stream()
                .filter(t -> ServingTicket.STATUS_RESOLVED.equals(t.getStatus())
                        || ServingTicket.STATUS_CLOSED.equals(t.getStatus()))
                .limit(40)
                .map(t -> toSummary(t, shopSeq.get(t.getId()), stats))
                .toList();

        Map<String, List<ServingTicket>> byAgent = rows.stream()
                .filter(t -> t.getAssignedTo() != null && OPEN_WORK.contains(t.getStatus()))
                .collect(Collectors.groupingBy(ServingTicket::getAssignedTo, LinkedHashMap::new, Collectors.toList()));

        List<SuperAdmin> staff = superAdminRepository.findAll().stream()
                .filter(SuperAdmin::isActive)
                .sorted(Comparator.comparing(SuperAdmin::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (agent) {
            staff = staff.stream().filter(s -> actor.getId().equals(s.getId())).toList();
        }

        List<ServingDtos.BoardAgentColumn> columns = new ArrayList<>();
        for (SuperAdmin member : staff) {
            List<ServingTicket> owned = byAgent.getOrDefault(member.getId(), List.of());
            int open = (int) owned.stream().filter(t -> ServingTicket.STATUS_OPEN.equals(t.getStatus())
                    || ServingTicket.STATUS_NEW.equals(t.getStatus())).count();
            int wait = (int) owned.stream().filter(t -> ServingTicket.STATUS_WAITING.equals(t.getStatus())).count();
            columns.add(new ServingDtos.BoardAgentColumn(
                    member.getId(),
                    member.getName(),
                    member.getEmail(),
                    SuperAdminDeskRoles.normalizeOrOwner(member.getDeskRole()),
                    open,
                    wait,
                    owned.stream().map(t -> toSummary(t, shopSeq.get(t.getId()), stats)).toList()
            ));
        }
        return new ServingDtos.BoardResponse(unassigned, columns, waiting, resolved);
    }

    public Map<String, Integer> loadFor(String superAdminId) {
        int open = (int) ticketRepository.countByAssignedToAndStatusIn(
                superAdminId, List.of(ServingTicket.STATUS_NEW, ServingTicket.STATUS_OPEN));
        int waiting = (int) ticketRepository.countByAssignedToAndStatusIn(
                superAdminId, List.of(ServingTicket.STATUS_WAITING));
        return Map.of("openCount", open, "waitingCount", waiting);
    }

    private ServingTicket latestOpenForConversation(String conversationId) {
        return ticketRepository.findByConversationIdOrderByCreatedAtDesc(conversationId).stream()
                .filter(t -> !ServingTicket.STATUS_CLOSED.equals(t.getStatus()))
                .findFirst()
                .orElse(null);
    }

    private String latestOrderId(String conversationId) {
        if (conversationId == null) {
            return null;
        }
        return supportService.messagesSince(conversationId, null).stream()
                .map(SupportMessageDto::orderCard)
                .filter(card -> card != null && card.orderId() != null && !card.orderId().isBlank())
                .reduce((first, second) -> second)
                .map(zelisline.ub.support.api.dto.SupportOrderCardDto::orderId)
                .orElse(null);
    }

    private ServingTicket newTicket(
            String type,
            String subject,
            String category,
            String priority,
            String businessId,
            String createdByKind,
            String createdBy,
            String requesterName
    ) {
        ServingTicket ticket = new ServingTicket();
        ticket.setTicketNumber(nextNumber());
        ticket.setType(type);
        ticket.setStatus(ServingTicket.STATUS_NEW);
        ticket.setPriority(priority);
        ticket.setCategory(category);
        ticket.setSubject(subject);
        ticket.setBusinessId(businessId);
        ticket.setCreatedByKind(createdByKind);
        ticket.setCreatedBy(createdBy);
        ticket.setRequesterName(requesterName);
        return ticket;
    }

    private int nextNumber() {
        return ticketRepository.findTopByOrderByTicketNumberDesc()
                .map(existing -> existing.getTicketNumber() + 1)
                .orElse(ServingTicketCounter.FIRST_NUMBER);
    }

    private void applyStatus(ServingTicket ticket, String status, SuperAdmin actor, String reason) {
        String previous = ticket.getStatus();
        if (ServingTicket.STATUS_CLOSED.equals(status)
                && actor != null
                && SuperAdminDeskRoles.isAgent(actor.getDeskRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a lead can close a ticket");
        }
        if (ServingTicket.STATUS_CLOSED.equals(previous) && !ServingTicket.STATUS_OPEN.equals(status)) {
            if (actor == null || SuperAdminDeskRoles.isAgent(actor.getDeskRole())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a lead can reopen a closed ticket");
            }
        }
        ticket.setStatus(status);
        if (ServingTicket.STATUS_RESOLVED.equals(status)) {
            ticket.setResolvedAt(Instant.now());
        } else if (ServingTicket.STATUS_CLOSED.equals(status)) {
            ticket.setClosedAt(Instant.now());
            if (ticket.getResolvedAt() == null) {
                ticket.setResolvedAt(Instant.now());
            }
        } else {
            ticket.setResolvedAt(null);
            ticket.setClosedAt(null);
        }
        ticketRepository.save(ticket);
        String payload = previous + " → " + status + (reason == null ? "" : " (" + reason + ")");
        recordEvent(
                ticket,
                actor == null ? null : actor.getId(),
                actor == null ? "System" : actor.getName(),
                ServingTicketEvent.KIND_STATUS,
                payload
        );
    }

    private void recordEvent(ServingTicket ticket, String actorId, String actorName, String kind, String payload) {
        ServingTicketEvent event = new ServingTicketEvent();
        event.setTicketId(ticket.getId());
        event.setActorId(actorId);
        event.setActorName(actorName);
        event.setKind(kind);
        event.setPayload(payload == null ? null : (payload.length() > 500 ? payload.substring(0, 500) : payload));
        eventRepository.save(event);
    }

    private ServingTicket requireTicket(String id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
    }

    private SuperAdmin requireActiveStaff(String id) {
        SuperAdmin admin = superAdminRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff not found"));
        if (!admin.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff is inactive");
        }
        return admin;
    }

    private Business requireBusiness(String businessId) {
        return businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
    }

    private void assertCanView(SuperAdmin actor, ServingTicket ticket) {
        if (SuperAdminDeskRoles.isAgent(actor.getDeskRole()) && !visibleToAgent(ticket, actor.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This ticket is not assigned to you");
        }
    }

    private void assertCanWork(SuperAdmin actor, ServingTicket ticket) {
        assertCanView(actor, ticket);
        if (SuperAdminDeskRoles.isAgent(actor.getDeskRole())
                && ticket.getAssignedTo() != null
                && !actor.getId().equals(ticket.getAssignedTo())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This ticket is not assigned to you");
        }
    }

    private void assertCanAssign(SuperAdmin actor, ServingTicket ticket) {
        if (SuperAdminDeskRoles.canAssignAny(actor.getDeskRole())) {
            return;
        }
        if (ticket.getAssignedTo() == null || actor.getId().equals(ticket.getAssignedTo())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot reassign this ticket");
    }

    private static boolean visibleToAgent(ServingTicket ticket, String agentId) {
        return ticket.getAssignedTo() == null || agentId.equals(ticket.getAssignedTo());
    }

    private static boolean matchesAssignee(ServingTicket ticket, String assigneeFilter) {
        if (assigneeFilter == null) {
            return true;
        }
        if ("unassigned".equalsIgnoreCase(assigneeFilter)) {
            return ticket.getAssignedTo() == null;
        }
        if ("me".equalsIgnoreCase(assigneeFilter)) {
            return false;
        }
        return assigneeFilter.equals(ticket.getAssignedTo());
    }

    private boolean matchesQuery(ServingTicket ticket, String query) {
        String number = ticket.displayNumber().toLowerCase(Locale.ROOT);
        return number.contains(query)
                || (ticket.getSubject() != null && ticket.getSubject().toLowerCase(Locale.ROOT).contains(query))
                || (ticket.getRequesterName() != null && ticket.getRequesterName().toLowerCase(Locale.ROOT).contains(query))
                || (ticket.getShopperName() != null && ticket.getShopperName().toLowerCase(Locale.ROOT).contains(query));
    }

    private ServingDtos.TicketSummary toSummary(ServingTicket ticket) {
        List<ServingTicketPoint> points = pointRepository.findByTicketIdOrderBySeqAsc(ticket.getId());
        int done = (int) points.stream().filter(ServingTicketPoint::isDone).count();
        return toSummary(ticket, computeShopSeq(ticket), points.size(), done);
    }

    private ServingDtos.TicketSummary toSummary(ServingTicket ticket, Integer shopSeq, Map<String, int[]> stats) {
        int[] row = stats.get(ticket.getId());
        int count = row == null ? 0 : row[0];
        int done = row == null ? 0 : row[1];
        return toSummary(ticket, shopSeq, count, done);
    }

    private ServingDtos.TicketSummary toSummary(
            ServingTicket ticket,
            Integer shopSeq,
            int pointCount,
            int doneCount
    ) {
        String businessName = null;
        if (ticket.getBusinessId() != null) {
            businessName = businessRepository.findByIdAndDeletedAtIsNull(ticket.getBusinessId())
                    .map(Business::getName)
                    .orElse(null);
        }
        String assignedName = null;
        if (ticket.getAssignedTo() != null) {
            assignedName = superAdminRepository.findById(ticket.getAssignedTo())
                    .map(SuperAdmin::getName)
                    .orElse(null);
        }
        Instant lastActivity = ticket.getUpdatedAt() != null ? ticket.getUpdatedAt() : ticket.getCreatedAt();
        return new ServingDtos.TicketSummary(
                ticket.getId(),
                ticket.getTicketNumber(),
                ticket.displayNumber(),
                ticket.getType(),
                ticket.getStatus(),
                ticket.getPriority(),
                ticket.getCategory(),
                ticket.getSubject(),
                ticket.getBusinessId(),
                businessName,
                ticket.getRequesterName(),
                ticket.getRequesterEmail(),
                ticket.getRequesterPhone(),
                ticket.getShopperName(),
                ticket.getShopperPhone(),
                ticket.getOrderId(),
                ticket.getAssignedTo(),
                assignedName,
                ticket.getConversationId(),
                ticket.getContactMessageId(),
                lastActivity,
                ticket.getCreatedAt(),
                ticket.getUpdatedAt(),
                shopSeq,
                pointCount,
                doneCount
        );
    }

    private ServingDtos.TicketDetail toDetail(
            ServingTicket ticket,
            List<SupportMessageDto> messages,
            List<ServingDtos.TicketNote> notes,
            List<ServingDtos.TicketEvent> events
    ) {
        List<ServingTicketPoint> points = pointRepository.findByTicketIdOrderBySeqAsc(ticket.getId());
        int done = (int) points.stream().filter(ServingTicketPoint::isDone).count();
        ServingDtos.TicketSummary summary = toSummary(ticket, computeShopSeq(ticket), points.size(), done);
        return new ServingDtos.TicketDetail(
                summary,
                messages,
                notes,
                events,
                points.stream().map(this::toPoint).toList()
        );
    }

    private ServingDtos.TicketPoint toPoint(ServingTicketPoint point) {
        return new ServingDtos.TicketPoint(
                point.getId(),
                point.getSeq(),
                point.getTitle(),
                point.getDetail(),
                point.getStatus(),
                point.getSource(),
                point.getCompletedAt(),
                point.getCompletedByName(),
                point.getCompletedByKind()
        );
    }

    private ServingDtos.TicketNote toNote(ServingTicketNote note) {
        return new ServingDtos.TicketNote(
                note.getId(),
                note.getAuthorId(),
                note.getAuthorName(),
                note.getBody(),
                note.getCreatedAt()
        );
    }

    private ServingDtos.TicketEvent toEvent(ServingTicketEvent event) {
        return new ServingDtos.TicketEvent(
                event.getId(),
                event.getKind(),
                event.getActorId(),
                event.getActorName(),
                event.getPayload(),
                event.getCreatedAt()
        );
    }

    private static String firstSubject(SupportConversation conversation, SupportMessageDto message) {
        if (conversation.getSubject() != null && !conversation.getSubject().isBlank()) {
            return trimSubject(conversation.getSubject());
        }
        if (message != null && message.body() != null && !message.body().isBlank()) {
            return trimSubject(message.body());
        }
        if (conversation.getLastMessagePreview() != null && !conversation.getLastMessagePreview().isBlank()) {
            return trimSubject(conversation.getLastMessagePreview());
        }
        if (SupportConversation.TYPE_VISITOR.equals(conversation.getConversationType())) {
            return "Visitor chat";
        }
        return "Support request";
    }

    private static String trimSubject(String raw) {
        String value = raw == null ? "" : raw.trim().replace('\n', ' ');
        if (value.isEmpty()) {
            return "Support request";
        }
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static String normalizeType(String raw) {
        String value = raw == null ? ServingTicket.TYPE_TENANT : raw.trim().toUpperCase(Locale.ROOT);
        if (ServingTicket.TYPE_TENANT.equals(value) || ServingTicket.TYPE_SHOPPER.equals(value)) {
            return value;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type must be TENANT or SHOPPER");
    }

    private static String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status is required");
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (ServingTicket.STATUS_NEW.equals(value)
                || ServingTicket.STATUS_OPEN.equals(value)
                || ServingTicket.STATUS_WAITING.equals(value)
                || ServingTicket.STATUS_RESOLVED.equals(value)
                || ServingTicket.STATUS_CLOSED.equals(value)) {
            return value;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status");
    }

    private static String normalizePriority(String raw) {
        if (raw == null || raw.isBlank()) {
            return ServingTicket.PRIORITY_NORMAL;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (ServingTicket.PRIORITY_LOW.equals(value)
                || ServingTicket.PRIORITY_NORMAL.equals(value)
                || ServingTicket.PRIORITY_HIGH.equals(value)
                || ServingTicket.PRIORITY_URGENT.equals(value)) {
            return value;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown priority");
    }

    private static String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return ServingTicket.CATEGORY_OTHER;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (ServingTicket.CATEGORY_BILLING.equals(value)
                || ServingTicket.CATEGORY_ONBOARDING.equals(value)
                || ServingTicket.CATEGORY_BUG.equals(value)
                || ServingTicket.CATEGORY_DOMAIN.equals(value)
                || ServingTicket.CATEGORY_MARKETPLACE.equals(value)
                || ServingTicket.CATEGORY_OTHER.equals(value)) {
            return value;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown category");
    }

    private static String requireSubject(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subject is required");
        }
        return value.length() > 191 ? value.substring(0, 191) : value;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void applyDrafts(ServingTicket ticket, ServingAgendaOrganizer.Extraction extraction) {
        List<ServingTicketPoint> existing = pointRepository.findByTicketIdOrderBySeqAsc(ticket.getId());
        boolean keepDone = existing.stream().anyMatch(ServingTicketPoint::isDone);
        if (keepDone) {
            pointRepository.deleteByTicketIdAndStatus(ticket.getId(), ServingTicketPoint.STATUS_OPEN);
        } else {
            pointRepository.deleteAll(existing);
        }
        pointRepository.flush();

        List<ServingTicketPoint> kept = pointRepository.findByTicketIdOrderBySeqAsc(ticket.getId());
        int cursor = 10_001;
        for (ServingTicketPoint point : kept) {
            point.setSeq(cursor++);
            pointRepository.save(point);
        }
        pointRepository.flush();
        int seq = 1;
        for (ServingTicketPoint point : kept) {
            point.setSeq(seq++);
            pointRepository.save(point);
        }
        pointRepository.flush();

        String source = ServingAgendaOrganizer.SOURCE_AI.equals(extraction.source())
                ? ServingTicketPoint.SOURCE_AI
                : ServingTicketPoint.SOURCE_HEURISTIC;
        for (ServingAgendaOrganizer.DraftPoint draft : extraction.points()) {
            ServingTicketPoint point = new ServingTicketPoint();
            point.setTicketId(ticket.getId());
            point.setSeq(seq++);
            point.setTitle(draft.title());
            point.setDetail(draft.detail());
            point.setStatus(ServingTicketPoint.STATUS_OPEN);
            point.setSource(source);
            pointRepository.save(point);
        }
    }

    private ServingDtos.TicketPoint markPoint(
            ServingTicket ticket,
            String pointId,
            boolean done,
            String actorId,
            String actorName,
            String actorKind
    ) {
        if (ServingTicket.STATUS_CLOSED.equals(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Closed tickets cannot change points");
        }
        ServingTicketPoint point = pointRepository.findById(pointId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Point not found"));
        if (!ticket.getId().equals(point.getTicketId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Point not found");
        }
        if (done) {
            if (!point.isDone()) {
                point.setStatus(ServingTicketPoint.STATUS_DONE);
                point.setCompletedAt(Instant.now());
                point.setCompletedBy(actorId);
                point.setCompletedByName(actorName);
                point.setCompletedByKind(actorKind);
                pointRepository.save(point);
                recordEvent(ticket, actorId, actorName, ServingTicketEvent.KIND_POINT, point.getSeq() + " done");
            }
        } else {
            if (point.isDone()) {
                point.setStatus(ServingTicketPoint.STATUS_OPEN);
                point.setCompletedAt(null);
                point.setCompletedBy(null);
                point.setCompletedByName(null);
                point.setCompletedByKind(null);
                pointRepository.save(point);
                recordEvent(ticket, actorId, actorName, ServingTicketEvent.KIND_POINT, point.getSeq() + " reopened");
            }
        }
        return toPoint(point);
    }

    private static String formatListMessage(List<ServingAgendaOrganizer.DraftPoint> points) {
        StringBuilder sb = new StringBuilder();
        sb.append("Here's the numbered list we're working through together. ");
        sb.append("Open Tickets in your shop to tick each one when it's done.\n");
        int n = 1;
        for (ServingAgendaOrganizer.DraftPoint point : points) {
            sb.append('\n').append(n++).append(". ").append(point.title());
            if (point.detail() != null && !point.detail().isBlank() && !point.detail().equals(point.title())) {
                sb.append('\n').append("   ").append(point.detail());
            }
        }
        return sb.toString();
    }

    private Map<String, Integer> shopSeqById(List<ServingTicket> tickets) {
        Map<String, Integer> out = new HashMap<>();
        Map<String, List<ServingTicket>> byShop = tickets.stream()
                .filter(t -> t.getBusinessId() != null)
                .collect(Collectors.groupingBy(
                        ServingTicket::getBusinessId,
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)));
        for (List<ServingTicket> group : byShop.values()) {
            group.sort(Comparator
                    .comparing(ServingTicket::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparingInt(ServingTicket::getTicketNumber));
            int n = 1;
            for (ServingTicket ticket : group) {
                out.put(ticket.getId(), n++);
            }
        }
        return out;
    }

    private Map<String, int[]> pointStats(List<ServingTicket> tickets) {
        List<String> ids = tickets.stream().map(ServingTicket::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, int[]> stats = new HashMap<>();
        for (ServingTicketPoint point : pointRepository.findByTicketIdIn(ids)) {
            int[] row = stats.computeIfAbsent(point.getTicketId(), key -> new int[2]);
            row[0]++;
            if (point.isDone()) {
                row[1]++;
            }
        }
        return stats;
    }

    private Integer computeShopSeq(ServingTicket ticket) {
        if (ticket.getBusinessId() == null) {
            return null;
        }
        List<ServingTicket> siblings = ticketRepository.findByBusinessIdOrderByCreatedAtAsc(ticket.getBusinessId());
        int n = 1;
        for (ServingTicket sibling : siblings) {
            if (ticket.getId().equals(sibling.getId())) {
                return n;
            }
            n++;
        }
        return null;
    }
}
