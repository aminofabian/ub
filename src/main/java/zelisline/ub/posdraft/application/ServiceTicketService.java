package zelisline.ub.posdraft.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.posdraft.PosDraftConstants;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.ServiceBoardResponse;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CancelTicketRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CreateServiceTicketRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.PaidTicketResponse;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.PayTicketRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.StartTicketRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TicketLineInput;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TicketLineResponse;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TicketResponse;
import zelisline.ub.posdraft.domain.PosDraft;
import zelisline.ub.posdraft.domain.PosDraftLine;
import zelisline.ub.posdraft.infrastructure.BranchPosSequenceAllocator;
import zelisline.ub.posdraft.repository.PosDraftLineRepository;
import zelisline.ub.posdraft.repository.PosDraftRepository;
import zelisline.ub.sales.api.dto.PostSaleLineRequest;
import zelisline.ub.sales.api.dto.PostSaleRequest;
import zelisline.ub.sales.application.SaleCreationOutcome;
import zelisline.ub.sales.application.SaleService;
import zelisline.ub.sales.domain.SaleLineKinds;
import zelisline.ub.serviceops.application.ServiceCatalogService;
import zelisline.ub.serviceops.domain.ServiceOffering;
import zelisline.ub.tenancy.application.BranchStationService;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Walk-in service tickets: the queue of a service business (ADR 0014).
 *
 * <p>A ticket is a held cart of kind {@code SERVICE} on the existing held-cart tables. It moves
 * {@code WAITING → IN_SERVICE → READY_TO_PAY} and ends {@code completed} (paid, with a sale) or
 * {@code cancelled}. The ticket is the operational record and may be changed until it is paid;
 * the sale it produces is the financial record.</p>
 */
@Service
@RequiredArgsConstructor
public class ServiceTicketService {

    private static final int MONEY_SCALE = 2;

    private final PosDraftRepository draftRepository;
    private final PosDraftLineRepository lineRepository;
    private final BranchPosSequenceAllocator sequenceAllocator;
    private final BranchRepository branchRepository;
    private final BranchStationService branchStationService;
    private final ServiceCatalogService serviceCatalogService;
    private final StaffProfileService staffProfileService;
    private final SaleService saleService;
    private final zelisline.ub.serviceops.application.ServiceSaleSupport serviceSaleSupport;
    private final zelisline.ub.audit.application.AuditEventPublisher auditEventPublisher;
    private final zelisline.ub.audit.application.AuditEventBuilder auditEventBuilder;

    /**
     * Every open ticket at a branch, oldest first, with the number of chairs. Not limited to
     * today: a ticket left open overnight still holds its chair and must stay visible so someone
     * can finish, pay or cancel it.
     */
    @Transactional(readOnly = true)
    public ServiceBoardResponse board(String businessId, String branchId) {
        int stations = branchStationService.stationCount(businessId, branchId);
        List<PosDraft> open = draftRepository.findOpenServiceTickets(businessId, branchId.trim());
        Map<String, String> names = staffNames(businessId, open);
        List<TicketResponse> tickets = new ArrayList<>(open.size());
        for (PosDraft ticket : open) {
            tickets.add(toResponse(ticket, activeLines(ticket.getId()), names));
        }
        return new ServiceBoardResponse(branchId.trim(), stations, tickets);
    }

    /** Opens a ticket. Sending the same {@code clientTicketId} again returns the first ticket. */
    @Transactional
    public TicketResponse create(String businessId, CreateServiceTicketRequest request, String userId) {
        String clientId = request.clientTicketId().trim();
        var existing = draftRepository.findByBusinessIdAndClientDraftId(businessId, clientId);
        if (existing.isPresent()) {
            PosDraft found = existing.get();
            if (!PosDraftConstants.KIND_SERVICE.equals(found.getDraftKind())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "That id is already used");
            }
            return toResponse(found, activeLines(found.getId()), staffNames(businessId, List.of(found)));
        }
        String branchId = request.branchId().trim();
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
        String requested = blankToNull(request.requestedStaffProfileId());
        if (requested != null) {
            requireBookable(businessId, requested);
        }

        PosDraft ticket = new PosDraft();
        ticket.setBusinessId(businessId);
        ticket.setBranchId(branchId);
        // Numbered per branch, like held carts (unique per branch since V372).
        ticket.setTicketNumber(sequenceAllocator.allocateTicketNumber(branchId));
        ticket.setStatus(PosDraftConstants.STATUS_PENDING);
        ticket.setDraftKind(PosDraftConstants.KIND_SERVICE);
        ticket.setServiceStage(PosDraftConstants.STAGE_WAITING);
        ticket.setCreatedBy(userId);
        ticket.setClientDraftId(clientId);
        ticket.setCustomerId(blankToNull(request.customerId()));
        ticket.setRequestedStaffProfileId(requested);
        try {
            ticket = draftRepository.saveAndFlush(ticket);
        } catch (DataIntegrityViolationException ex) {
            // Another till sent the same client id a moment ago.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Could not open the ticket; try again");
        }

        List<PosDraftLine> lines = new ArrayList<>();
        int index = 0;
        for (TicketLineInput input : request.lines()) {
            lines.add(buildLine(businessId, ticket, index++, input));
        }
        lineRepository.saveAll(lines);
        recomputeTotal(ticket, lines);
        ticket = draftRepository.saveAndFlush(ticket);
        return toResponse(ticket, lines, staffNames(businessId, List.of(ticket)));
    }

    /** Adds a service while the ticket is still open (the customer asks for a beard trim halfway). */
    @Transactional
    public TicketResponse addLine(String businessId, String ticketId, TicketLineInput input) {
        PosDraft ticket = requireOpen(businessId, ticketId);
        List<PosDraftLine> all = lineRepository.findByDraftIdOrderByLineIndexAsc(ticketId);
        int nextIndex = all.stream().mapToInt(PosDraftLine::getLineIndex).max().orElse(-1) + 1;
        PosDraftLine line = buildLine(businessId, ticket, nextIndex, input);
        if (!PosDraftConstants.STAGE_WAITING.equals(ticket.getServiceStage())) {
            // Already with someone: they must be able to do the extra service too.
            serviceSaleSupport.resolve(businessId, line.getServiceId(), ticket.getRequestedStaffProfileId());
            line.setPerformerStaffProfileId(ticket.getRequestedStaffProfileId());
        }
        lineRepository.save(line);
        List<PosDraftLine> active = activeLines(ticketId);
        recomputeTotal(ticket, active);
        ticket = draftRepository.saveAndFlush(ticket);
        return toResponse(ticket, active, staffNames(businessId, List.of(ticket)));
    }

    @Transactional
    public TicketResponse removeLine(String businessId, String ticketId, String lineId) {
        PosDraft ticket = requireOpen(businessId, ticketId);
        PosDraftLine line = lineRepository.findByIdAndDraftId(lineId, ticketId)
                .filter(l -> !l.isDeleted())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found"));
        if (lineRepository.countByDraftIdAndDeletedFalse(ticketId) <= 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A ticket needs at least one service; cancel the ticket instead");
        }
        line.setDeleted(true);
        lineRepository.save(line);
        List<PosDraftLine> active = activeLines(ticketId);
        recomputeTotal(ticket, active);
        ticket = draftRepository.saveAndFlush(ticket);
        return toResponse(ticket, active, staffNames(businessId, List.of(ticket)));
    }

    /**
     * The customer sits down: the ticket takes a chair and a staff member. Two customers cannot be
     * in one chair; the branch row is locked so two tills cannot both take the same chair.
     */
    @Transactional
    public TicketResponse start(String businessId, String ticketId, StartTicketRequest request) {
        PosDraft ticket = requireOpen(businessId, ticketId);
        if (!PosDraftConstants.STAGE_WAITING.equals(ticket.getServiceStage())) {
            if (PosDraftConstants.STAGE_IN_SERVICE.equals(ticket.getServiceStage())
                    && request.stationNo().equals(ticket.getStationNo())
                    && request.performerStaffProfileId().trim().equals(ticket.getRequestedStaffProfileId())) {
                // A double tap on Start.
                return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This ticket has already been started");
        }
        String performer = request.performerStaffProfileId().trim();
        List<PosDraftLine> lines = activeLines(ticketId);
        // The same checks payment will make: a current, customer-serving staff member who is
        // allowed to perform every service on the ticket. Failing here beats failing at the till.
        for (PosDraftLine line : lines) {
            serviceSaleSupport.resolve(businessId, line.getServiceId(), performer);
        }
        branchRepository.findByIdAndBusinessIdForUpdate(ticket.getBranchId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
        int stations = branchStationService.stationCount(businessId, ticket.getBranchId());
        if (request.stationNo() > stations) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This branch has " + stations + " chair" + (stations == 1 ? "" : "s"));
        }
        if (!draftRepository.findInServiceAtStation(businessId, ticket.getBranchId(), request.stationNo()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That chair is taken");
        }
        ticket.setStationNo(request.stationNo());
        ticket.setRequestedStaffProfileId(performer);
        ticket.setServiceStage(PosDraftConstants.STAGE_IN_SERVICE);
        ticket.setServiceStartedAt(Instant.now());
        for (PosDraftLine line : lines) {
            line.setPerformerStaffProfileId(performer);
        }
        lineRepository.saveAll(lines);
        ticket = draftRepository.saveAndFlush(ticket);
        return toResponse(ticket, lines, staffNames(businessId, List.of(ticket)));
    }

    /** The work is finished; the chair is free and the ticket waits to be paid. */
    @Transactional
    public TicketResponse finish(String businessId, String ticketId) {
        PosDraft ticket = requireOpen(businessId, ticketId);
        if (PosDraftConstants.STAGE_READY_TO_PAY.equals(ticket.getServiceStage())) {
            return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
        }
        if (!PosDraftConstants.STAGE_IN_SERVICE.equals(ticket.getServiceStage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Start the ticket first");
        }
        ticket.setServiceStage(PosDraftConstants.STAGE_READY_TO_PAY);
        ticket.setServiceFinishedAt(Instant.now());
        ticket = draftRepository.saveAndFlush(ticket);
        return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
    }

    /** Done was tapped by mistake: back to the chair, if it is still free. */
    @Transactional
    public TicketResponse reopen(String businessId, String ticketId) {
        PosDraft ticket = requireOpen(businessId, ticketId);
        if (PosDraftConstants.STAGE_IN_SERVICE.equals(ticket.getServiceStage())) {
            // A double tap.
            return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
        }
        if (!PosDraftConstants.STAGE_READY_TO_PAY.equals(ticket.getServiceStage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a finished ticket can be reopened");
        }
        branchRepository.findByIdAndBusinessIdForUpdate(ticket.getBranchId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
        if (ticket.getStationNo() != null && !draftRepository
                .findInServiceAtStation(businessId, ticket.getBranchId(), ticket.getStationNo()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That chair has been taken by someone else");
        }
        ticket.setServiceStage(PosDraftConstants.STAGE_IN_SERVICE);
        ticket.setServiceFinishedAt(null);
        ticket = draftRepository.saveAndFlush(ticket);
        return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
    }

    /** Cancels an unpaid ticket, with a reason; {@code abandoned} marks a customer who walked away. */
    @Transactional
    public TicketResponse cancel(String businessId, String ticketId, CancelTicketRequest request, String userId) {
        PosDraft ticket = requireTicket(businessId, ticketId);
        if (PosDraftConstants.STATUS_CANCELLED.equals(ticket.getStatus())) {
            return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
        }
        if (!ticket.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A paid ticket cannot be cancelled; void the sale");
        }
        boolean abandoned = request != null && Boolean.TRUE.equals(request.abandoned());
        ticket.setStatus(PosDraftConstants.STATUS_CANCELLED);
        if (abandoned) {
            ticket.setServiceStage(PosDraftConstants.STAGE_ABANDONED);
        }
        ticket.setCancelledBy(userId);
        ticket.setCancelledAt(Instant.now());
        String reason = request == null ? null : blankToNull(request.reason());
        ticket.setCancelledReason(reason != null ? reason : (abandoned ? "Customer left" : "Cancelled"));
        String stageWhenCancelled = ticket.getServiceStage();
        ticket = draftRepository.saveAndFlush(ticket);
        // A cancelled ticket is work that will never be paid for: keep a trace of who did it.
        java.util.Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("ticketNumber", ticket.getTicketNumber());
        state.put("stage", stageWhenCancelled);
        state.put("total", ticket.getGrandTotal().toPlainString());
        state.put("reason", ticket.getCancelledReason());
        auditEventPublisher.publish(auditEventBuilder
                .builder(zelisline.ub.audit.domain.AuditEventCategory.SALES, "service_ticket.cancelled",
                        zelisline.ub.audit.domain.AuditEventSeverity.WARN)
                .businessId(businessId)
                .branchId(ticket.getBranchId())
                .actor(userId, zelisline.ub.audit.domain.AuditEventActorType.USER)
                .target("service_ticket", ticket.getId())
                .targetLabel("Ticket " + ticket.getTicketNumber())
                .newState(state)
                .source("floor")
                .build());
        return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
    }

    /**
     * Takes payment: the ticket becomes a sale with one service line per ticket line, attributed
     * to the staff member on the ticket. Replaying the same idempotency key returns the same sale.
     */
    @Transactional(noRollbackFor = TicketRepricedException.class)
    public PaidTicketResponse pay(
            String businessId,
            String ticketId,
            PayTicketRequest request,
            String idempotencyKey,
            String userId,
            String roleId,
            String tillDeviceKey
    ) {
        PosDraft ticket = requireTicket(businessId, ticketId);
        if (PosDraftConstants.STATUS_CANCELLED.equals(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This ticket was cancelled");
        }
        List<PosDraftLine> lines = activeLines(ticketId);
        if (!ticket.isPending()) {
            // Already paid: hand back the sale it produced. Never ring it a second time, whatever
            // key this request carries.
            return new PaidTicketResponse(
                    toResponse(ticket, lines, staffNames(businessId, List.of(ticket))),
                    saleService.requireSale(businessId, ticket.getSaleId()));
        }
        if (ticket.isPending()) {
            if (PosDraftConstants.STAGE_WAITING.equals(ticket.getServiceStage())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Choose who served this customer before taking payment");
            }
            if (lines.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This ticket has no services");
            }
        }
        // A fixed-price service is sold at today's price. If the catalogue changed since the
        // ticket was opened, bring the ticket up to date and stop: the till shows the new total
        // and takes payment again, instead of the sale being refused with nothing to fix.
        List<String> changed = new ArrayList<>();
        for (PosDraftLine line : lines) {
            ServiceOffering service = serviceCatalogService.requireSellable(businessId, line.getServiceId());
            BigDecimal current = service.getPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            if (!service.isPriceIsVariable() && line.getUnitPrice().compareTo(current) != 0) {
                line.setUnitPrice(current);
                line.setLineTotal(current.multiply(line.getQuantity()).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
                changed.add(service.getName());
            }
        }
        if (!changed.isEmpty()) {
            lineRepository.saveAll(lines);
            recomputeTotal(ticket, lines);
            draftRepository.saveAndFlush(ticket);
            throw new TicketRepricedException("The price of " + String.join(", ", changed)
                    + " has changed. This ticket is now " + ticket.getGrandTotal().toPlainString()
                    + " — take payment again.");
        }
        List<PostSaleLineRequest> saleLines = new ArrayList<>(lines.size());
        for (PosDraftLine line : lines) {
            String performer = line.getPerformerStaffProfileId() != null
                    ? line.getPerformerStaffProfileId()
                    : ticket.getRequestedStaffProfileId();
            saleLines.add(new PostSaleLineRequest(
                    null, line.getQuantity(), line.getUnitPrice(), SaleLineKinds.SERVICE, null, null, null, null,
                    line.getServiceId(), performer));
        }
        PostSaleRequest saleRequest = new PostSaleRequest(
                ticket.getBranchId(),
                saleLines,
                request.payments(),
                request.clientSoldAt(),
                ticket.getCustomerId(),
                request.cashReceived(),
                null,
                request.tips());
        SaleCreationOutcome outcome =
                saleService.createSale(businessId, idempotencyKey, saleRequest, userId, roleId, tillDeviceKey);
        if (ticket.isPending()) {
            ticket.setStatus(PosDraftConstants.STATUS_COMPLETED);
            ticket.setSaleId(outcome.response().id());
            ticket.setCompletedAt(Instant.now());
            if (ticket.getServiceFinishedAt() == null) {
                ticket.setServiceFinishedAt(Instant.now());
            }
            ticket = draftRepository.saveAndFlush(ticket);
        }
        return new PaidTicketResponse(
                toResponse(ticket, lines, staffNames(businessId, List.of(ticket))), outcome.response());
    }

    /** Branch and stage of a ticket, for the controller's branch-lock and permission checks. */
    @Transactional(readOnly = true)
    public TicketRef ref(String businessId, String ticketId) {
        PosDraft ticket = requireTicket(businessId, ticketId);
        return new TicketRef(ticket.getBranchId(), ticket.getServiceStage(), ticket.isPending());
    }

    public record TicketRef(String branchId, String stage, boolean open) {
    }

    // ------------------------------------------------------------------ helpers

    private PosDraftLine buildLine(String businessId, PosDraft ticket, int index, TicketLineInput input) {
        ServiceOffering service = serviceCatalogService.requireSellable(businessId, input.serviceId().trim());
        BigDecimal price = service.getPrice();
        if (service.isPriceIsVariable() && input.unitPrice() != null && input.unitPrice().signum() > 0) {
            price = input.unitPrice();
        }
        if (price == null || price.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the price for " + service.getName());
        }
        BigDecimal unitPrice = price.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        PosDraftLine line = new PosDraftLine();
        line.setDraftId(ticket.getId());
        line.setBusinessId(businessId);
        line.setLineIndex(index);
        line.setLineKind(SaleLineKinds.SERVICE);
        line.setServiceId(service.getId());
        line.setItemName(service.getName().length() > 500 ? service.getName().substring(0, 500) : service.getName());
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(unitPrice);
        line.setDiscountAmount(BigDecimal.ZERO);
        line.setLineTotal(unitPrice);
        return line;
    }

    private void requireBookable(String businessId, String staffProfileId) {
        if (!staffProfileService.findBookableProfileIds(businessId).contains(staffProfileId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Staff member not found, or not marked as serving customers");
        }
    }

    private PosDraft requireTicket(String businessId, String ticketId) {
        return draftRepository.findByIdAndBusinessId(ticketId, businessId)
                .filter(d -> PosDraftConstants.KIND_SERVICE.equals(d.getDraftKind()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
    }

    private PosDraft requireOpen(String businessId, String ticketId) {
        PosDraft ticket = requireTicket(businessId, ticketId);
        if (!ticket.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This ticket is closed");
        }
        return ticket;
    }

    private List<PosDraftLine> activeLines(String ticketId) {
        return lineRepository.findByDraftIdOrderByLineIndexAsc(ticketId).stream()
                .filter(line -> !line.isDeleted())
                .toList();
    }

    private static void recomputeTotal(PosDraft ticket, List<PosDraftLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (PosDraftLine line : lines) {
            if (!line.isDeleted()) {
                total = total.add(line.getLineTotal());
            }
        }
        total = total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        ticket.setSubTotal(total);
        ticket.setGrandTotal(total);
    }

    private Map<String, String> staffNames(String businessId, List<PosDraft> tickets) {
        List<String> ids = tickets.stream()
                .map(PosDraft::getRequestedStaffProfileId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        return ids.isEmpty() ? Map.of() : staffProfileService.displayNamesByIds(businessId, ids);
    }

    private static TicketResponse toResponse(PosDraft ticket, List<PosDraftLine> lines, Map<String, String> names) {
        List<TicketLineResponse> lineDtos = new ArrayList<>(lines.size());
        for (PosDraftLine line : lines) {
            if (!line.isDeleted()) {
                lineDtos.add(new TicketLineResponse(
                        line.getId(), line.getServiceId(), line.getItemName(), line.getUnitPrice(), line.getLineTotal()));
            }
        }
        String staffId = ticket.getRequestedStaffProfileId();
        return new TicketResponse(
                ticket.getId(),
                ticket.getBranchId(),
                ticket.getTicketNumber(),
                ticket.getServiceStage(),
                ticket.getStatus(),
                ticket.getStationNo(),
                staffId,
                staffId == null ? null : names.get(staffId),
                ticket.getCustomerId(),
                ticket.getGrandTotal(),
                ticket.getCreatedAt(),
                ticket.getServiceStartedAt(),
                ticket.getServiceFinishedAt(),
                ticket.getSaleId(),
                ticket.getVersion(),
                lineDtos);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
