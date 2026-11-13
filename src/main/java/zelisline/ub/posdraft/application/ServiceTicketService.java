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
import zelisline.ub.sales.api.dto.PostSalePaymentRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TakeDepositRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.FloorStaffResponse;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.RescheduleRequest;
import zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CreateAppointmentRequest;
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
    private final zelisline.ub.serviceops.application.HeldMoneyService heldMoneyService;

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
        Map<String, List<PosDraftLine>> linesByTicket = new java.util.LinkedHashMap<>();
        java.util.Set<String> serviceIds = new java.util.HashSet<>();
        for (PosDraft ticket : open) {
            List<PosDraftLine> lines = activeLines(ticket.getId());
            linesByTicket.put(ticket.getId(), lines);
            for (PosDraftLine line : lines) {
                serviceIds.add(line.getServiceId());
            }
        }
        Map<String, Integer> minutes = serviceCatalogService.durationsByIds(businessId, serviceIds);
        Map<String, String> statuses = staffProfileService.floorStatuses(businessId);
        Map<String, String> allNames = staffProfileService.displayNamesByIds(businessId, statuses.keySet());
        List<FloorStaffResponse> staff = new ArrayList<>(statuses.size());
        int available = 0;
        for (Map.Entry<String, String> entry : statuses.entrySet()) {
            staff.add(new FloorStaffResponse(
                    entry.getKey(), allNames.getOrDefault(entry.getKey(), "Staff"), entry.getValue()));
            if ("AVAILABLE".equals(entry.getValue())) {
                available++;
            }
        }
        Map<String, Integer> waits = QueueEstimator.estimate(
                open, linesByTicket, minutes, Math.min(stations, available), Instant.now());
        List<TicketResponse> tickets = new ArrayList<>(open.size());
        for (PosDraft ticket : open) {
            tickets.add(toResponse(ticket, linesByTicket.get(ticket.getId()), names, waits.get(ticket.getId())));
        }
        return new ServiceBoardResponse(branchId.trim(), stations, tickets, staff);
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
        String redoOf = blankToNull(request.redoOfSaleId());
        if (redoOf != null) {
            // Must be a sale of this business; 404 otherwise.
            saleService.requireSale(businessId, redoOf);
        }

        PosDraft ticket = new PosDraft();
        ticket.setRedoOfSaleId(redoOf);
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
        // A booked customer who walks in and sits straight down has, in effect, arrived.
        if (!PosDraftConstants.STAGE_WAITING.equals(ticket.getServiceStage())
                && !PosDraftConstants.STAGE_BOOKED.equals(ticket.getServiceStage())) {
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
        PosDraft done = requireTicket(businessId, ticketId);
        if (PosDraftConstants.STAGE_REDO_DONE.equals(done.getServiceStage())) {
            // A double tap on Done for a redo.
            return toResponse(done, activeLines(ticketId), staffNames(businessId, List.of(done)));
        }
        PosDraft ticket = requireOpen(businessId, ticketId);
        if (PosDraftConstants.STAGE_READY_TO_PAY.equals(ticket.getServiceStage())) {
            return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
        }
        if (!PosDraftConstants.STAGE_IN_SERVICE.equals(ticket.getServiceStage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Start the ticket first");
        }
        ticket.setServiceFinishedAt(Instant.now());
        if (ticket.getRedoOfSaleId() != null) {
            // A redo is not charged: it closes here, with who did it and when, and no sale.
            ticket.setServiceStage(PosDraftConstants.STAGE_REDO_DONE);
            ticket.setStatus(PosDraftConstants.STATUS_COMPLETED);
            ticket.setCompletedAt(ticket.getServiceFinishedAt());
            releaseDepositToWallet(businessId, ticket);
        } else {
            ticket.setServiceStage(PosDraftConstants.STAGE_READY_TO_PAY);
        }
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
        // The shop did not do the work, so a deposit goes back to the customer as credit.
        releaseDepositToWallet(businessId, ticket);
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
        if (ticket.getRedoOfSaleId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A redo is not charged");
        }
        if (!ticket.isPending()) {
            // Already paid: hand back the sale it produced. Never ring it a second time, whatever
            // key this request carries.
            return new PaidTicketResponse(
                    toResponse(ticket, lines, staffNames(businessId, List.of(ticket))),
                    saleService.requireSale(businessId, ticket.getSaleId()));
        }
        if (ticket.isPending()) {
            if (PosDraftConstants.STAGE_BOOKED.equals(ticket.getServiceStage())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This customer is booked but has not arrived yet");
            }
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
        // A deposit held for this ticket is moved to the customer's wallet and spent from there,
        // ahead of whatever else they hand over. Anything left over stays in their wallet.
        List<PostSalePaymentRequest> payments = new ArrayList<>();
        if (PosDraftConstants.DEPOSIT_HELD.equals(ticket.getDepositState())) {
            BigDecimal deposit = ticket.getDepositAmount();
            releaseDepositToWallet(businessId, ticket);
            ticket.setDepositState(PosDraftConstants.DEPOSIT_APPLIED);
            payments.add(new PostSalePaymentRequest(
                    zelisline.ub.sales.SalesConstants.PAYMENT_METHOD_CUSTOMER_WALLET,
                    deposit.min(ticket.getGrandTotal()), null));
        }
        if (request.payments() != null) {
            payments.addAll(request.payments());
        }
        if (payments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter how the customer paid");
        }
        PostSaleRequest saleRequest = new PostSaleRequest(
                ticket.getBranchId(),
                saleLines,
                payments,
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

    // ------------------------------------------------------------------ deposits

    /**
     * Takes money in advance against a ticket or booking (ADR 0015). One deposit per ticket; it is
     * held for the named customer until the visit is paid, cancelled or missed.
     */
    @Transactional
    public TicketResponse takeDeposit(
            String businessId, String ticketId, TakeDepositRequest request, String userId, String tillDeviceKey) {
        PosDraft ticket = requireOpen(businessId, ticketId);
        BigDecimal amount = zelisline.ub.serviceops.application.HeldMoneyService.requireAmount(request.amount());
        if (ticket.getDepositState() != null) {
            if (PosDraftConstants.DEPOSIT_HELD.equals(ticket.getDepositState())
                    && amount.compareTo(ticket.getDepositAmount()) == 0) {
                // A double tap.
                return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This ticket already has a deposit");
        }
        if (ticket.getRedoOfSaleId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A redo is not charged");
        }
        if (ticket.getCustomerId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose the customer first: a deposit is held in their name");
        }
        String method = request.method().trim().toLowerCase(java.util.Locale.ROOT);
        heldMoneyService.receive(
                businessId, ticket.getBranchId(), zelisline.ub.finance.LedgerAccountCodes.CUSTOMER_DEPOSITS,
                zelisline.ub.serviceops.application.HeldMoneyService.SOURCE_DEPOSIT_TAKEN, ticket.getId(),
                amount, method, "Deposit on ticket " + ticket.getTicketNumber(), userId, tillDeviceKey);
        ticket.setDepositAmount(amount);
        ticket.setDepositMethod(method);
        ticket.setDepositState(PosDraftConstants.DEPOSIT_HELD);
        // The version check on this save stops two tills taking a deposit on one ticket: the
        // loser rolls back, journal and drawer included.
        ticket = draftRepository.saveAndFlush(ticket);
        return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
    }

    /** A held deposit goes to the customer's wallet. Does nothing when there is none. */
    private void releaseDepositToWallet(String businessId, PosDraft ticket) {
        if (!PosDraftConstants.DEPOSIT_HELD.equals(ticket.getDepositState())) {
            return;
        }
        heldMoneyService.releaseToWallet(
                businessId, ticket.getBranchId(), zelisline.ub.finance.LedgerAccountCodes.CUSTOMER_DEPOSITS,
                zelisline.ub.serviceops.application.HeldMoneyService.SOURCE_DEPOSIT_RELEASED, ticket.getId(),
                ticket.getCustomerId(), ticket.getDepositAmount(),
                zelisline.ub.credits.WalletTxnTypes.CREDIT_DEPOSIT_RELEASE,
                "Deposit released, ticket " + ticket.getTicketNumber());
        ticket.setDepositState(PosDraftConstants.DEPOSIT_CREDITED);
    }

    // ------------------------------------------------------------- queue controls

    /** Moves a waiting ticket up (or back down) the queue. 0 is the normal queue. */
    @Transactional
    public TicketResponse setPriority(String businessId, String ticketId, int priority) {
        PosDraft ticket = requireOpen(businessId, ticketId);
        ticket.setQueuePriority(priority);
        ticket = draftRepository.saveAndFlush(ticket);
        return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
    }

    // --------------------------------------------------------------- appointments

    /**
     * Books a staff member for a customer at a time. The slot runs for the planned minutes of the
     * services booked. A staff member cannot be booked twice for overlapping times.
     */
    @Transactional
    public TicketResponse book(String businessId, CreateAppointmentRequest request, String userId) {
        String staffId = request.staffProfileId().trim();
        // The staff row lock is the first statement, so the overlap check below sees every
        // booking that committed before this one got the lock.
        staffProfileService.lockForLedger(businessId, staffId);
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
        requireBookable(businessId, staffId);
        requireSensibleStart(request.start());

        PosDraft ticket = new PosDraft();
        ticket.setBusinessId(businessId);
        ticket.setBranchId(branchId);
        ticket.setTicketNumber(sequenceAllocator.allocateTicketNumber(branchId));
        ticket.setStatus(PosDraftConstants.STATUS_PENDING);
        ticket.setDraftKind(PosDraftConstants.KIND_SERVICE);
        ticket.setServiceStage(PosDraftConstants.STAGE_BOOKED);
        ticket.setCreatedBy(userId);
        ticket.setClientDraftId(clientId);
        ticket.setCustomerId(blankToNull(request.customerId()));
        ticket.setRequestedStaffProfileId(staffId);
        ticket.setBookingName(request.name().trim());
        ticket.setBookingPhone(blankToNull(request.phone()));
        ticket.setBookingSource("STAFF");
        ticket.setSendReminder(Boolean.TRUE.equals(request.sendReminder()) && blankToNull(request.phone()) != null);
        ticket.setScheduledStart(request.start());
        ticket.setScheduledEnd(request.start());
        ticket = draftRepository.saveAndFlush(ticket);

        List<PosDraftLine> lines = new ArrayList<>();
        int index = 0;
        for (TicketLineInput input : request.lines()) {
            PosDraftLine line = buildLine(businessId, ticket, index++, input);
            // The same checks payment will make, now rather than on the day.
            serviceSaleSupport.resolve(businessId, line.getServiceId(), staffId);
            line.setPerformerStaffProfileId(staffId);
            lines.add(line);
        }
        lineRepository.saveAll(lines);
        recomputeTotal(ticket, lines);
        ticket.setScheduledEnd(request.start().plus(plannedMinutes(businessId, lines), java.time.temporal.ChronoUnit.MINUTES));
        requireFree(businessId, staffId, ticket.getScheduledStart(), ticket.getScheduledEnd(), ticket.getId());
        ticket = draftRepository.saveAndFlush(ticket);
        return toResponse(ticket, lines, staffNames(businessId, List.of(ticket)));
    }

    /** Appointments at a branch whose start falls in the window, earliest first. */
    @Transactional(readOnly = true)
    public List<TicketResponse> appointments(String businessId, String branchId, Instant from, Instant to) {
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId.trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
        List<PosDraft> rows = draftRepository.findAppointmentsBetween(businessId, branchId.trim(), from, to);
        Map<String, String> names = staffNames(businessId, rows);
        List<TicketResponse> out = new ArrayList<>(rows.size());
        for (PosDraft row : rows) {
            out.add(toResponse(row, activeLines(row.getId()), names));
        }
        return out;
    }

    /** Moves a booking to another time, and optionally to another staff member. */
    @Transactional
    public TicketResponse reschedule(String businessId, String ticketId, RescheduleRequest request) {
        PosDraft peek = requireTicket(businessId, ticketId);
        String staffId = blankToNull(request.staffProfileId()) != null
                ? request.staffProfileId().trim()
                : peek.getRequestedStaffProfileId();
        // Lock first; then re-read the booking so the checks below use current data.
        staffProfileService.lockForLedger(businessId, staffId);
        PosDraft ticket = requireBooked(businessId, ticketId);
        requireSensibleStart(request.start());
        List<PosDraftLine> lines = activeLines(ticketId);
        if (!staffId.equals(ticket.getRequestedStaffProfileId())) {
            requireBookable(businessId, staffId);
            for (PosDraftLine line : lines) {
                serviceSaleSupport.resolve(businessId, line.getServiceId(), staffId);
                line.setPerformerStaffProfileId(staffId);
            }
            lineRepository.saveAll(lines);
            ticket.setRequestedStaffProfileId(staffId);
        }
        Instant end = request.start().plus(plannedMinutes(businessId, lines), java.time.temporal.ChronoUnit.MINUTES);
        requireFree(businessId, staffId, request.start(), end, ticket.getId());
        ticket.setScheduledStart(request.start());
        ticket.setScheduledEnd(end);
        // A new time deserves a new reminder.
        ticket.setReminderSentAt(null);
        ticket = draftRepository.saveAndFlush(ticket);
        return toResponse(ticket, lines, staffNames(businessId, List.of(ticket)));
    }

    /** The customer has come in: the booking joins the queue as a waiting ticket. */
    @Transactional
    public TicketResponse arrive(String businessId, String ticketId) {
        PosDraft ticket = requireOpen(businessId, ticketId);
        if (!PosDraftConstants.STAGE_BOOKED.equals(ticket.getServiceStage())) {
            // A double tap, or already on the floor.
            return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
        }
        ticket.setServiceStage(PosDraftConstants.STAGE_WAITING);
        ticket = draftRepository.saveAndFlush(ticket);
        return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
    }

    /** The customer did not turn up: the booking is closed and remembered as a no-show. */
    @Transactional
    public TicketResponse noShow(String businessId, String ticketId, String userId, boolean keepDepositAsCredit) {
        PosDraft ticket = requireTicket(businessId, ticketId);
        if (PosDraftConstants.STAGE_NO_SHOW.equals(ticket.getServiceStage())) {
            return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
        }
        if (!ticket.isPending() || !PosDraftConstants.STAGE_BOOKED.equals(ticket.getServiceStage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a booking that has not arrived can be a no-show");
        }
        ticket.setStatus(PosDraftConstants.STATUS_CANCELLED);
        ticket.setServiceStage(PosDraftConstants.STAGE_NO_SHOW);
        ticket.setCancelledBy(userId);
        ticket.setCancelledAt(Instant.now());
        ticket.setCancelledReason("Did not turn up");
        if (PosDraftConstants.DEPOSIT_HELD.equals(ticket.getDepositState())) {
            if (keepDepositAsCredit) {
                releaseDepositToWallet(businessId, ticket);
            } else {
                // The slot was held and lost: the shop keeps the deposit as income.
                heldMoneyService.forfeit(
                        businessId, ticket.getBranchId(), zelisline.ub.finance.LedgerAccountCodes.CUSTOMER_DEPOSITS,
                        zelisline.ub.serviceops.application.HeldMoneyService.SOURCE_DEPOSIT_FORFEITED,
                        ticket.getId(), ticket.getDepositAmount(),
                        "Deposit kept after no-show, ticket " + ticket.getTicketNumber());
                ticket.setDepositState(PosDraftConstants.DEPOSIT_FORFEITED);
            }
        }
        ticket = draftRepository.saveAndFlush(ticket);
        return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
    }

    private PosDraft requireBooked(String businessId, String ticketId) {
        PosDraft ticket = requireOpen(businessId, ticketId);
        if (!PosDraftConstants.STAGE_BOOKED.equals(ticket.getServiceStage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking has already arrived");
        }
        return ticket;
    }

    private void requireFree(String businessId, String staffId, Instant start, Instant end, String exceptTicketId) {
        for (PosDraft other : draftRepository.findBookedOverlapping(businessId, staffId, start, end)) {
            if (!other.getId().equals(exceptTicketId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "They are already booked at that time");
            }
        }
    }

    private static void requireSensibleStart(Instant start) {
        Instant now = Instant.now();
        if (start.isBefore(now.minus(5, java.time.temporal.ChronoUnit.MINUTES))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a time that has not passed");
        }
        if (start.isAfter(now.plus(366, java.time.temporal.ChronoUnit.DAYS))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a time within the next year");
        }
    }

    private long plannedMinutes(String businessId, List<PosDraftLine> lines) {
        Map<String, Integer> minutes = serviceCatalogService.durationsByIds(
                businessId, lines.stream().map(PosDraftLine::getServiceId).toList());
        long total = 0;
        for (PosDraftLine line : lines) {
            total += minutes.getOrDefault(line.getServiceId(), 0);
        }
        return Math.max(total, 5);
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
        return toResponse(ticket, lines, names, null);
    }

    private static TicketResponse toResponse(
            PosDraft ticket, List<PosDraftLine> lines, Map<String, String> names, Integer estimatedWait) {
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
                lineDtos,
                ticket.getScheduledStart(),
                ticket.getScheduledEnd(),
                ticket.getBookingName(),
                ticket.getBookingPhone(),
                ticket.getQueuePriority(),
                estimatedWait,
                ticket.getDepositAmount(),
                ticket.getDepositState(),
                ticket.getRedoOfSaleId(),
                ticket.isSendReminder());
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
