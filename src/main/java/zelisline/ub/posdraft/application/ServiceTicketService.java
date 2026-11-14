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
    private final zelisline.ub.serviceops.application.ServiceResourceService resourceService;
    private final zelisline.ub.catalog.repository.ItemRepository itemRepository;
    private final zelisline.ub.discounts.application.DiscountResolutionService discountResolutionService;
    private final zelisline.ub.serviceops.repository.ServiceResourceRepository resourceRepository;
    private final zelisline.ub.serviceops.application.StaffEarningsService staffEarningsService;
    private final zelisline.ub.credits.repository.CustomerRepository customerRepository;
    private final zelisline.ub.serviceops.application.ServicePackageService servicePackageService;
    private final zelisline.ub.serviceops.application.ServiceHelperRoleService helperRoleService;

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
        ticket.setCustomerId(requireOwnCustomer(businessId, request.customerId()));
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
        if (PosDraftConstants.STAGE_IN_SERVICE.equals(line.getLineStage())
                || PosDraftConstants.LINE_DONE.equals(line.getLineStage())) {
            if (activeLines(ticketId).size() > 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This service has been started; it can no longer be removed");
            }
        }
        if (activeLines(ticketId).size() <= 1) {
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

    // ------------------------------------------------------ products on a visit

    /**
     * Adds a product the customer is buying on this visit (hair food after a haircut). It is paid
     * for with the services, as one sale. The price is today's shelf price; adding the same
     * product again increases its quantity. Stock is taken when the visit is paid, not before.
     */
    @Transactional
    public TicketResponse addProduct(
            String businessId, String ticketId, zelisline.ub.posdraft.api.dto.ServiceTicketDtos.AddProductRequest request) {
        PosDraft ticket = requireOpen(businessId, ticketId);
        if (ticket.getRedoOfSaleId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A free redo cannot carry products; ring them on the till");
        }
        var item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(request.itemId().trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product not found"));
        String cannotSell = zelisline.ub.catalog.application.ItemSellability.sellabilityViolation(item);
        if (cannotSell != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, cannotSell);
        }
        BigDecimal price = currentProductPrice(businessId, ticket.getBranchId(), item.getId(), item.getName());
        List<PosDraftLine> all = lineRepository.findByDraftIdOrderByLineIndexAsc(ticketId);
        PosDraftLine line = all.stream()
                .filter(l -> !l.isDeleted() && !SaleLineKinds.isService(l.getLineKind()) && item.getId().equals(l.getItemId()))
                .findFirst()
                .orElse(null);
        if (line == null) {
            if (productLines(ticketId).size() >= 30) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A visit can carry up to 30 different products");
            }
            line = new PosDraftLine();
            line.setDraftId(ticket.getId());
            line.setBusinessId(businessId);
            line.setLineIndex(all.stream().mapToInt(PosDraftLine::getLineIndex).max().orElse(-1) + 1);
            line.setLineKind(SaleLineKinds.ITEM);
            line.setItemId(item.getId());
            line.setItemName(item.getName().length() > 255 ? item.getName().substring(0, 255) : item.getName());
            line.setQuantity(BigDecimal.ZERO);
        }
        line.setQuantity(line.getQuantity().add(request.quantity()));
        line.setUnitPrice(price);
        line.setLineTotal(price.multiply(line.getQuantity()).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        lineRepository.saveAndFlush(line);
        recomputeTotal(ticket, null);
        PosDraft saved = draftRepository.saveAndFlush(ticket);
        return toResponse(saved, activeLines(ticketId), staffNames(businessId, List.of(saved)));
    }

    @Transactional
    public TicketResponse removeProduct(String businessId, String ticketId, String lineId) {
        PosDraft ticket = requireOpen(businessId, ticketId);
        PosDraftLine line = lineRepository.findByIdAndDraftId(lineId, ticketId)
                .filter(l -> !l.isDeleted() && !SaleLineKinds.isService(l.getLineKind()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found on this visit"));
        line.setDeleted(true);
        lineRepository.saveAndFlush(line);
        recomputeTotal(ticket, null);
        PosDraft saved = draftRepository.saveAndFlush(ticket);
        return toResponse(saved, activeLines(ticketId), staffNames(businessId, List.of(saved)));
    }

    /** Today's price for a product at a branch, after any running discount. */
    private BigDecimal currentProductPrice(String businessId, String branchId, String itemId, String name) {
        var resolved = discountResolutionService.resolveForItem(businessId, itemId, branchId);
        BigDecimal price = resolved.finalPrice() != null ? resolved.finalPrice() : resolved.regularPrice();
        if (price == null || price.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Set a selling price for " + name + " first");
        }
        return price.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------ one service at a time

    @Transactional(readOnly = true)
    public TicketResponse get(String businessId, String ticketId) {
        PosDraft ticket = requireTicket(businessId, ticketId);
        return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
    }

    /**
     * Says who does one service of a visit, and optionally where, without starting it. A small
     * shop uses this to pick the barber and take payment straight away.
     */
    @Transactional
    public TicketResponse assignLine(
            String businessId, String ticketId, String lineId,
            zelisline.ub.posdraft.api.dto.ServiceTicketDtos.LineAssignmentRequest request) {
        PosDraft ticket = requireOpen(businessId, ticketId);
        PosDraftLine line = requireLine(ticketId, lineId);
        if (PosDraftConstants.STAGE_IN_SERVICE.equals(line.getLineStage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This service is in progress; mark it done before changing who does it");
        }
        String performer = blankToNull(request.performerStaffProfileId());
        if (performer != null) {
            serviceSaleSupport.resolve(businessId, line.getServiceId(), performer);
            dropHelper(line, performer);
            line.setPerformerStaffProfileId(performer);
        }
        if (request.resourceId() != null) {
            String resourceId = blankToNull(request.resourceId());
            if (resourceId != null) {
                var resource = resourceRepository.findByIdAndBusinessId(resourceId, businessId)
                        .filter(found -> found.getBranchId().equals(ticket.getBranchId()))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Resource not found"));
                resourceId = resource.getId();
            }
            line.setResourceId(resourceId);
        }
        lineRepository.save(line);
        PosDraft saved = draftRepository.saveAndFlush(ticket);
        return toResponse(saved, activeLines(ticketId), staffNames(businessId, List.of(saved)));
    }

    /**
     * Starts one service of a visit: this person, at this chair or bed, now. Refused when the
     * person is in the middle of another service or the resource is taken, being prepared or out
     * of use — two separate questions, both of which must be yes.
     */
    @Transactional
    public TicketResponse startLine(
            String businessId, String ticketId, String lineId,
            zelisline.ub.posdraft.api.dto.ServiceTicketDtos.LineAssignmentRequest request) {
        PosDraft ticket = requireOpen(businessId, ticketId);
        PosDraftLine line = requireLine(ticketId, lineId);
        String performer = blankToNull(request.performerStaffProfileId()) != null
                ? request.performerStaffProfileId().trim()
                : line.getPerformerStaffProfileId();
        if (performer == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose who is doing " + line.getItemName());
        }
        String resourceId = request.resourceId() != null ? blankToNull(request.resourceId()) : line.getResourceId();
        if (PosDraftConstants.STAGE_IN_SERVICE.equals(line.getLineStage())) {
            if (performer.equals(line.getPerformerStaffProfileId())
                    && java.util.Objects.equals(resourceId, line.getResourceId())) {
                // A double tap on Start.
                return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This service has already been started");
        }
        serviceSaleSupport.resolve(businessId, line.getServiceId(), performer);
        // Always the person first, then the place, so two tills cannot wait on each other.
        requireStaffFree(businessId, performer, line.getId());
        Integer stationNo = null;
        if (resourceId != null) {
            var resource = resourceService.claim(businessId, ticket.getBranchId(), resourceId, line.getId(), line.getServiceId());
            stationNo = resource.getStationNo();
        }
        Instant now = Instant.now();
        dropHelper(line, performer);
        line.setPerformerStaffProfileId(performer);
        line.setResourceId(resourceId);
        line.setLineStage(PosDraftConstants.STAGE_IN_SERVICE);
        if (line.getStartedAt() == null) {
            line.setStartedAt(now);
        }
        line.setFinishedAt(null);
        lineRepository.saveAndFlush(line);
        List<PosDraftLine> lines = activeLines(ticketId);
        restage(ticket, lines);
        if (stationNo != null) {
            ticket.setStationNo(stationNo);
        }
        if (ticket.getRequestedStaffProfileId() == null) {
            ticket.setRequestedStaffProfileId(performer);
        }
        PosDraft saved = draftRepository.saveAndFlush(ticket);
        return toResponse(saved, lines, staffNames(businessId, List.of(saved)));
    }

    /** One service is finished: its person and its chair or bed are free, whatever else is still going on. */
    @Transactional
    public TicketResponse finishLine(String businessId, String ticketId, String lineId) {
        PosDraft ticket = requireOpen(businessId, ticketId);
        PosDraftLine line = requireLine(ticketId, lineId);
        if (PosDraftConstants.LINE_DONE.equals(line.getLineStage())) {
            return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
        }
        if (!PosDraftConstants.STAGE_IN_SERVICE.equals(line.getLineStage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Start this service first");
        }
        line.setLineStage(PosDraftConstants.LINE_DONE);
        line.setFinishedAt(Instant.now());
        lineRepository.saveAndFlush(line);
        List<PosDraftLine> lines = activeLines(ticketId);
        boolean allDone = lines.stream().allMatch(l -> PosDraftConstants.LINE_DONE.equals(l.getLineStage()));
        if (allDone && ticket.getRedoOfSaleId() != null) {
            // The last service of a free redo: close it the way Done on the ticket does.
            ticket.setServiceStage(PosDraftConstants.STAGE_IN_SERVICE);
            draftRepository.saveAndFlush(ticket);
            return finish(businessId, ticketId);
        }
        restage(ticket, lines);
        PosDraft saved = draftRepository.saveAndFlush(ticket);
        return toResponse(saved, lines, staffNames(businessId, List.of(saved)));
    }

    /**
     * What the visit would pay out if it were paid now: each person's commission on each service
     * and what is left for the shop. The same calculation the sale then records.
     */
    @Transactional(readOnly = true)
    public zelisline.ub.posdraft.api.dto.ServiceTicketDtos.VisitEarningsResponse earnings(String businessId, String ticketId) {
        PosDraft ticket = requireTicket(businessId, ticketId);
        List<PosDraftLine> lines = activeLines(ticketId);
        Map<String, String> names = staffNames(businessId, List.of(ticket));
        List<zelisline.ub.posdraft.api.dto.ServiceTicketDtos.EarningsLine> out = new ArrayList<>();
        Map<String, BigDecimal> perStaff = new java.util.LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        for (PosDraftLine line : lines) {
            List<zelisline.ub.posdraft.api.dto.ServiceTicketDtos.EarningsShare> shares = new ArrayList<>();
            BigDecimal lineCommission = BigDecimal.ZERO;
            for (var share : staffEarningsService.previewServiceLine(businessId,
                    new zelisline.ub.serviceops.application.StaffEarningsService.SaleLine(
                            line.getId(), true, line.getServiceId(), line.getPerformerStaffProfileId(),
                            line.getLineTotal(), BigDecimal.ZERO, line.getQuantity(),
                            zelisline.ub.serviceops.domain.ServiceHelpers.decode(line.getServiceHelpers()),
                            line.getPerformerAmount()))) {
                shares.add(new zelisline.ub.posdraft.api.dto.ServiceTicketDtos.EarningsShare(
                        share.staffProfileId(), names.getOrDefault(share.staffProfileId(), "Staff"),
                        share.commission(), share.rule()));
                lineCommission = lineCommission.add(share.commission());
                perStaff.merge(share.staffProfileId(), share.commission(), BigDecimal::add);
            }
            out.add(new zelisline.ub.posdraft.api.dto.ServiceTicketDtos.EarningsLine(
                    line.getId(), line.getItemName(), line.getLineTotal(), shares,
                    line.getLineTotal().subtract(lineCommission)));
            total = total.add(line.getLineTotal());
            commission = commission.add(lineCommission);
        }
        List<zelisline.ub.posdraft.api.dto.ServiceTicketDtos.StaffEarning> staff = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : perStaff.entrySet()) {
            staff.add(new zelisline.ub.posdraft.api.dto.ServiceTicketDtos.StaffEarning(
                    entry.getKey(), names.getOrDefault(entry.getKey(), "Staff"), entry.getValue()));
        }
        return new zelisline.ub.posdraft.api.dto.ServiceTicketDtos.VisitEarningsResponse(
                ticket.getId(), total, commission, total.subtract(commission), out, staff);
    }

    private PosDraftLine requireLine(String ticketId, String lineId) {
        return lineRepository.findByIdAndDraftId(lineId, ticketId)
                .filter(l -> !l.isDeleted() && SaleLineKinds.isService(l.getLineKind()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found"));
    }

    /**
     * A person does one service at a time. Their row is locked first and the check is a locking
     * read, so two tills cannot both start them.
     */
    private void requireStaffFree(String businessId, String staffProfileId, String exceptLineId) {
        staffProfileService.lockForLedger(businessId, staffProfileId);
        for (PosDraftLine busy : lineRepository.findInServiceByPerformer(businessId, staffProfileId)) {
            if (!busy.getId().equals(exceptLineId) && !busy.getDraftId().equals(draftIdOf(exceptLineId))) {
                String name = staffProfileService.displayNamesByIds(businessId, List.of(staffProfileId))
                        .getOrDefault(staffProfileId, "They");
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        name + " is still doing " + busy.getItemName() + "; mark it done first");
            }
        }
    }

    /** Services of the same visit do not block each other in the simple one-person path. */
    private String draftIdOf(String lineId) {
        return lineId == null ? null : lineRepository.findById(lineId).map(PosDraftLine::getDraftId).orElse(null);
    }

    /** The person serving cannot also be listed as helping on the same service. */
    private static void dropHelper(PosDraftLine line, String performer) {
        if (!performer.equals(line.getPerformerStaffProfileId())) {
            // An amount was agreed for the person who led it, not for whoever replaces them.
            line.setPerformerAmount(null);
        }
        var helpers = zelisline.ub.serviceops.domain.ServiceHelpers.decode(line.getServiceHelpers());
        if (helpers.removeIf(h -> h.staffProfileId().equals(performer))) {
            line.setServiceHelpers(zelisline.ub.serviceops.domain.ServiceHelpers.encode(helpers));
        }
    }

    /**
     * The visit's stage follows its services: in service while any is under way, ready to pay
     * once all are done, otherwise waiting (for the first, or between two).
     */
    private static void restage(PosDraft ticket, List<PosDraftLine> lines) {
        boolean any = lines.stream().anyMatch(l -> PosDraftConstants.STAGE_IN_SERVICE.equals(l.getLineStage()));
        boolean all = !lines.isEmpty()
                && lines.stream().allMatch(l -> PosDraftConstants.LINE_DONE.equals(l.getLineStage()));
        if (any) {
            ticket.setServiceStage(PosDraftConstants.STAGE_IN_SERVICE);
            if (ticket.getServiceStartedAt() == null) {
                ticket.setServiceStartedAt(Instant.now());
            }
            ticket.setServiceFinishedAt(null);
        } else if (all) {
            ticket.setServiceStage(PosDraftConstants.STAGE_READY_TO_PAY);
            ticket.setServiceFinishedAt(Instant.now());
        } else {
            ticket.setServiceStage(PosDraftConstants.STAGE_WAITING);
            ticket.setStationNo(null);
        }
    }

    /**
     * Sets everyone working on one service in one go: the first person leads, the rest help,
     * sharing it equally unless shares are given. An amount, where given, is what that person is
     * paid for this service instead of what their plan gives; together the amounts cannot come to
     * more than the service costs.
     */
    @Transactional
    public TicketResponse setTeam(
            String businessId, String ticketId, String lineId,
            List<zelisline.ub.posdraft.api.dto.ServiceTicketDtos.LineHelper> members) {
        PosDraft ticket = requireOpen(businessId, ticketId);
        PosDraftLine line = requireLine(ticketId, lineId);
        String lead = members.get(0).staffProfileId().trim();
        if (PosDraftConstants.STAGE_IN_SERVICE.equals(line.getLineStage())
                && !lead.equals(line.getPerformerStaffProfileId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This service is in progress; mark it done before changing who leads it");
        }
        java.util.Set<String> named = new java.util.HashSet<>();
        BigDecimal equal = new BigDecimal("100").divide(BigDecimal.valueOf(members.size()), 2, RoundingMode.DOWN);
        List<zelisline.ub.serviceops.domain.ServiceHelpers.Helper> helpers = new ArrayList<>();
        BigDecimal shares = BigDecimal.ZERO;
        BigDecimal amounts = BigDecimal.ZERO;
        for (int i = 0; i < members.size(); i++) {
            var member = members.get(i);
            String staffId = member.staffProfileId().trim();
            if (!named.add(staffId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name each person once");
            }
            serviceSaleSupport.resolve(businessId, line.getServiceId(), staffId);
            if (member.amount() != null) {
                amounts = amounts.add(member.amount());
            }
            if (i > 0) {
                BigDecimal share = (member.sharePercent() == null ? equal : member.sharePercent())
                        .setScale(2, RoundingMode.HALF_UP);
                shares = shares.add(share);
                helpers.add(new zelisline.ub.serviceops.domain.ServiceHelpers.Helper(staffId, share, member.amount()));
            }
        }
        if (shares.compareTo(new BigDecimal("99")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The shares must leave something for the person leading the service");
        }
        if (amounts.compareTo(line.getLineTotal()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Those amounts come to more than " + line.getItemName() + " costs ("
                            + line.getLineTotal().toPlainString() + ")");
        }
        line.setPerformerStaffProfileId(lead);
        line.setPerformerAmount(members.get(0).amount());
        line.setServiceHelpers(zelisline.ub.serviceops.domain.ServiceHelpers.encode(helpers));
        lineRepository.save(line);
        PosDraft saved = draftRepository.saveAndFlush(ticket);
        return toResponse(saved, activeLines(ticketId), staffNames(businessId, List.of(saved)));
    }

    /**
     * Several people worked on one service (one washes, one cuts, one finishes): names everyone
     * who shared it with the person serving, and what each gets.
     *
     * <p>A helper is paid one of three ways. By a role the owner set beforehand (an after-shave
     * at 50 shillings, a groomer at 20% of the price): the pay is worked out here from the role,
     * so anyone at the till may use it. By an amount typed at the till: a commission decision,
     * allowed only with {@code maySetAmounts}, though an amount already agreed for that person on
     * this service may be sent back unchanged by anyone. Or by their own plan, on their share of
     * the service; with no shares given those are split equally. An empty list means the person
     * serving did it alone.</p>
     *
     * <p>A role or a typed amount is paid out of what the person leading earns on the service,
     * never out of the shop's part: a role's percentage is a percentage of that, and together the
     * helpers cannot be given more than it. Where the person leading earns no commission (they
     * are on a salary), a set amount is the shop's to pay, up to the price.</p>
     */
    @Transactional
    public TicketResponse setHelpers(
            String businessId, String ticketId, String lineId,
            List<zelisline.ub.posdraft.api.dto.ServiceTicketDtos.LineHelper> asked, boolean maySetAmounts) {
        PosDraft ticket = requireOpen(businessId, ticketId);
        PosDraftLine line = lineRepository.findByIdAndDraftId(lineId, ticketId)
                .filter(l -> !l.isDeleted())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found"));
        String lead = line.getPerformerStaffProfileId() != null
                ? line.getPerformerStaffProfileId()
                : ticket.getRequestedStaffProfileId();
        java.util.Set<String> named = new java.util.HashSet<>();
        if (lead != null) {
            named.add(lead);
        }
        Map<String, BigDecimal> agreed = new java.util.HashMap<>();
        for (var helper : zelisline.ub.serviceops.domain.ServiceHelpers.decode(line.getServiceHelpers())) {
            if (helper.amount() != null) {
                agreed.put(helper.staffProfileId(), helper.amount());
            }
        }
        BigDecimal price = line.getLineTotal();
        // Only those paid by their plan share the price with the person leading.
        long byPlan = asked.stream()
                .filter(h -> (h.roleId() == null || h.roleId().isBlank()) && h.amount() == null)
                .count();
        BigDecimal equal = new BigDecimal("100").divide(BigDecimal.valueOf(byPlan + 1L), 2, RoundingMode.DOWN);
        List<zelisline.ub.serviceops.domain.ServiceHelpers.Helper> helpers = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal amounts = line.getPerformerAmount() == null ? BigDecimal.ZERO : line.getPerformerAmount();
        // What the person leading earns on this service before anyone helps: what helpers share.
        BigDecimal leadEarns = BigDecimal.ZERO;
        if (lead != null) {
            for (var share : staffEarningsService.previewServiceLine(businessId,
                    new zelisline.ub.serviceops.application.StaffEarningsService.SaleLine(
                            line.getId(), true, line.getServiceId(), lead, price, BigDecimal.ZERO,
                            line.getQuantity(), List.of(), line.getPerformerAmount()))) {
                leadEarns = leadEarns.add(share.commission());
            }
        }
        BigDecimal toHelpers = BigDecimal.ZERO;
        for (zelisline.ub.posdraft.api.dto.ServiceTicketDtos.LineHelper helper : asked) {
            String staffId = helper.staffProfileId().trim();
            if (!named.add(staffId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name each person once");
            }
            serviceSaleSupport.resolve(businessId, line.getServiceId(), staffId);
            BigDecimal amount = helper.amount();
            BigDecimal share = helper.sharePercent() == null ? equal : helper.sharePercent();
            if (helper.roleId() != null && !helper.roleId().isBlank()) {
                amount = helperRoleService.require(businessId, helper.roleId()).payFor(leadEarns);
                share = SET_AMOUNT_SHARE;
            } else if (amount != null) {
                BigDecimal before = agreed.get(staffId);
                if (!maySetAmounts && (before == null || before.compareTo(amount) != 0)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "Only someone who sets commission can type what a helper is paid. Use a helper role instead.");
                }
                share = SET_AMOUNT_SHARE;
            }
            share = share.setScale(2, RoundingMode.HALF_UP);
            total = total.add(share);
            if (amount != null) {
                amounts = amounts.add(amount);
                toHelpers = toHelpers.add(amount);
            }
            helpers.add(new zelisline.ub.serviceops.domain.ServiceHelpers.Helper(staffId, share, amount));
        }
        if (total.compareTo(new BigDecimal("99")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The shares must leave something for the person serving");
        }
        if (amounts.compareTo(price) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Those amounts come to more than " + line.getItemName() + " costs ("
                            + price.toPlainString() + ")");
        }
        if (line.getPerformerAmount() == null && leadEarns.signum() > 0 && toHelpers.compareTo(leadEarns) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The helpers would get " + toHelpers.toPlainString() + ", more than the "
                            + leadEarns.toPlainString() + " earned on " + line.getItemName()
                            + " by the person leading it");
        }
        line.setServiceHelpers(zelisline.ub.serviceops.domain.ServiceHelpers.encode(helpers));
        lineRepository.save(line);
        ticket = draftRepository.saveAndFlush(ticket);
        return toResponse(ticket, activeLines(ticketId), staffNames(businessId, List.of(ticket)));
    }

    /**
     * The share recorded for a helper paid a set amount. It only has to be a valid share: such a
     * helper takes nothing from the price the person leading earns on.
     */
    private static final BigDecimal SET_AMOUNT_SHARE = BigDecimal.ONE.setScale(2);

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
        // One person, one chair for the whole ticket: the simple path of a small shop. The
        // person must not be in the middle of another service, and the chair is taken as a resource.
        requireStaffFree(businessId, performer, null);
        resourceService.ensureForStations(businessId, ticket.getBranchId());
        var chair = resourceRepository
                .findByBusinessIdAndBranchIdAndStationNo(businessId, ticket.getBranchId(), request.stationNo())
                .map(found -> resourceService.claim(businessId, found.getBranchId(), found.getId(), null, null))
                .orElse(null);
        Instant startedNow = Instant.now();
        ticket.setStationNo(request.stationNo());
        ticket.setRequestedStaffProfileId(performer);
        ticket.setServiceStage(PosDraftConstants.STAGE_IN_SERVICE);
        ticket.setServiceStartedAt(startedNow);
        for (PosDraftLine line : lines) {
            if (PosDraftConstants.LINE_DONE.equals(line.getLineStage())) {
                // Already done by someone earlier in the visit: theirs stays theirs.
                continue;
            }
            line.setPerformerStaffProfileId(performer);
            line.setLineStage(PosDraftConstants.STAGE_IN_SERVICE);
            line.setStartedAt(startedNow);
            line.setFinishedAt(null);
            line.setResourceId(chair == null ? null : chair.getId());
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
        List<PosDraftLine> finishing = activeLines(ticketId);
        for (PosDraftLine line : finishing) {
            if (!PosDraftConstants.LINE_DONE.equals(line.getLineStage())) {
                line.setLineStage(PosDraftConstants.LINE_DONE);
                line.setFinishedAt(ticket.getServiceFinishedAt());
            }
        }
        lineRepository.saveAll(finishing);
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
        // Back in the chair: the last service done is in progress again, if its person and place are free.
        List<PosDraftLine> reopening = activeLines(ticketId);
        PosDraftLine last = reopening.stream()
                .filter(l -> l.getFinishedAt() != null)
                .max(java.util.Comparator.comparing(PosDraftLine::getFinishedAt))
                .orElse(null);
        if (last != null) {
            if (last.getPerformerStaffProfileId() != null) {
                requireStaffFree(businessId, last.getPerformerStaffProfileId(), last.getId());
            }
            if (last.getResourceId() != null) {
                resourceService.claim(businessId, ticket.getBranchId(), last.getResourceId(), last.getId(), null);
            }
            last.setLineStage(PosDraftConstants.STAGE_IN_SERVICE);
            last.setFinishedAt(null);
            lineRepository.save(last);
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
            if (lines.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This ticket has no services");
            }
            // Every service is rung against whoever did it. The person who opened the ticket, or
            // the one the customer asked for, is not assumed to have done the work.
            for (PosDraftLine line : lines) {
                if (line.getPerformerStaffProfileId() == null || line.getPerformerStaffProfileId().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Choose who did " + line.getItemName() + " before taking payment");
                }
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
        // Products are sold at today's shelf price as well.
        List<PosDraftLine> products = productLines(ticketId);
        for (PosDraftLine product : products) {
            BigDecimal current = currentProductPrice(businessId, ticket.getBranchId(), product.getItemId(), product.getItemName());
            if (product.getUnitPrice().compareTo(current) != 0) {
                product.setUnitPrice(current);
                product.setLineTotal(current.multiply(product.getQuantity()).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
                changed.add(product.getItemName());
            }
        }
        if (!changed.isEmpty()) {
            lineRepository.saveAll(products);
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
                    line.getServiceId(), performer, null, null,
                    zelisline.ub.serviceops.domain.ServiceHelpers.decode(line.getServiceHelpers()).stream()
                            .map(h -> new zelisline.ub.sales.api.dto.PostSaleLineHelper(
                                    h.staffProfileId(), h.sharePercent(), h.amount()))
                            .toList(),
                    line.getPerformerAmount()));
        }
        for (PosDraftLine product : products) {
            saleLines.add(PostSaleLineRequest.catalogItem(product.getItemId(), product.getQuantity(), product.getUnitPrice()));
        }
        // A deposit held for this ticket is moved to the customer's wallet and spent from there,
        // ahead of whatever else they hand over. Anything left over stays in their wallet.
        List<PostSalePaymentRequest> payments = new ArrayList<>();
        BigDecimal fromWallet = BigDecimal.ZERO;
        if (PosDraftConstants.DEPOSIT_HELD.equals(ticket.getDepositState())) {
            fromWallet = fromWallet.add(ticket.getDepositAmount());
            releaseDepositToWallet(businessId, ticket);
            ticket.setDepositState(PosDraftConstants.DEPOSIT_APPLIED);
        }
        // A prepaid session works the same way: its value is released to the wallet and spent.
        String packageId = blankToNull(request.packageId());
        if (packageId != null && ticket.getRedeemedPackageId() == null) {
            if (ticket.getCustomerId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Choose the customer on the ticket before using their package");
            }
            fromWallet = fromWallet.add(servicePackageService.redeemSession(
                    businessId, packageId, ticket.getCustomerId(),
                    lines.stream().map(PosDraftLine::getServiceId).toList(), ticket.getBranchId()));
            ticket.setRedeemedPackageId(packageId);
        }
        if (fromWallet.signum() > 0) {
            payments.add(new PostSalePaymentRequest(
                    zelisline.ub.sales.SalesConstants.PAYMENT_METHOD_CUSTOMER_WALLET,
                    fromWallet.min(ticket.getGrandTotal()), null));
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
                // Amounts set on the visit were authorised when they were typed, not by whoever takes the money.
                zelisline.ub.serviceops.application.EarningsApproval.approved(() ->
                        saleService.createSale(businessId, idempotencyKey, saleRequest, userId, roleId, tillDeviceKey));
        if (!outcome.createdNew()) {
            // This key already belongs to another sale. Nothing was rung for this ticket, so it
            // must not be closed against that sale, and a deposit or session released above must
            // not stay released: failing here rolls all of it back.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This payment was already used for another sale; take payment again");
        }
        if (ticket.isPending()) {
            ticket.setStatus(PosDraftConstants.STATUS_COMPLETED);
            ticket.setSaleId(outcome.response().id());
            ticket.setCompletedAt(Instant.now());
            if (ticket.getServiceFinishedAt() == null) {
                ticket.setServiceFinishedAt(Instant.now());
            }
            // Paid: anything still marked in progress is over, and its chair or bed is free.
            for (PosDraftLine line : lines) {
                if (!PosDraftConstants.LINE_DONE.equals(line.getLineStage())) {
                    line.setLineStage(PosDraftConstants.LINE_DONE);
                    if (line.getFinishedAt() == null) {
                        line.setFinishedAt(ticket.getCompletedAt());
                    }
                }
            }
            lineRepository.saveAll(lines);
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
        return book(businessId, request, userId, "STAFF");
    }

    /** @param source {@code STAFF} at the counter or by phone, {@code ONLINE} from the public booking page */
    @Transactional
    public TicketResponse book(String businessId, CreateAppointmentRequest request, String userId, String source) {
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
        ticket.setCustomerId(requireOwnCustomer(businessId, request.customerId()));
        ticket.setRequestedStaffProfileId(staffId);
        ticket.setBookingName(request.name().trim());
        ticket.setBookingPhone(blankToNull(request.phone()));
        ticket.setBookingSource(source);
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
        String resourceId = blankToNull(request.resourceId());
        if (resourceId != null) {
            // The place is held as well as the person: a free therapist is no use without a free bed.
            requireResourceFree(businessId, branchId, resourceId, lines,
                    ticket.getScheduledStart(), ticket.getScheduledEnd(), ticket.getId());
            for (PosDraftLine line : lines) {
                line.setResourceId(resourceId);
            }
            lineRepository.saveAll(lines);
        }
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
        // Lock the staff row first. The overlap check below is a locking read, and the version
        // check on the save catches a booking that someone else changed in the meantime.
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
        String heldResource = lines.stream().map(PosDraftLine::getResourceId)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (heldResource != null) {
            requireResourceFree(businessId, ticket.getBranchId(), heldResource, lines, request.start(), end, ticket.getId());
        }
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

    /** A ticket may only name a customer of this business; money is later held in their name. */
    private String requireOwnCustomer(String businessId, String customerId) {
        String id = blankToNull(customerId);
        if (id == null) {
            return null;
        }
        customerRepository.findByIdAndBusinessIdAndDeletedAtIsNull(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer not found"));
        return id;
    }

    private PosDraft requireBooked(String businessId, String ticketId) {
        PosDraft ticket = requireOpen(businessId, ticketId);
        if (!PosDraftConstants.STAGE_BOOKED.equals(ticket.getServiceStage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking has already arrived");
        }
        return ticket;
    }

    /** The resource row is locked, then a locking read looks for another booking holding it then. */
    private void requireResourceFree(
            String businessId, String branchId, String resourceId, List<PosDraftLine> lines,
            Instant start, Instant end, String exceptTicketId) {
        var resource = resourceRepository.findByIdAndBusinessIdForUpdate(resourceId, businessId)
                .filter(found -> found.getBranchId().equals(branchId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Resource not found"));
        if (!resource.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, resource.getName() + " is not in use");
        }
        if (resource.getBlockedUntil() != null && resource.getBlockedUntil().isAfter(start)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, resource.getName() + " is out of use then");
        }
        for (PosDraftLine line : lines) {
            String needs = serviceCatalogService.requireSellable(businessId, line.getServiceId()).getResourceType();
            if (needs != null && !needs.equals(resource.getResourceType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, line.getItemName() + " needs a "
                        + needs.toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ", and " + resource.getName()
                        + " is not one");
            }
        }
        // The cleaning time after the booking before is part of what it holds.
        Instant paddedStart = start.minus(resource.getCleanupMinutes(), java.time.temporal.ChronoUnit.MINUTES);
        Instant paddedEnd = end.plus(resource.getCleanupMinutes(), java.time.temporal.ChronoUnit.MINUTES);
        for (PosDraft other : draftRepository.findBookedOverlappingResource(businessId, resourceId, paddedStart, paddedEnd)) {
            if (!other.getId().equals(exceptTicketId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, resource.getName() + " is already booked at that time");
            }
        }
    }

    /**
     * Everything on a branch's calendar in a window: bookings, services under way and done,
     * staff time off and resources out of use, with the people and places to lay them out by.
     */
    @Transactional
    public zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CalendarResponse calendar(
            String businessId, String branchId, Instant from, Instant to) {
        String branch = branchId.trim();
        var resources = resourceService.list(businessId, branch);
        List<zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CalendarEntry> entries = new ArrayList<>();
        java.util.Set<String> ticketIds = new java.util.HashSet<>();
        for (PosDraft d : draftRepository.findAppointmentsBetween(businessId, branch, from, to)) {
            boolean booked = PosDraftConstants.STAGE_BOOKED.equals(d.getServiceStage()) && d.isPending();
            boolean missed = PosDraftConstants.STAGE_NO_SHOW.equals(d.getServiceStage());
            if (!booked && !missed) {
                // Arrived: its services show from when they actually start. Cancelled: gone.
                continue;
            }
            List<PosDraftLine> lines = activeLines(d.getId());
            ticketIds.add(d.getId());
            entries.add(new zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CalendarEntry(
                    booked ? "BOOKED" : "NO_SHOW", d.getId(), d.getTicketNumber(),
                    lines.stream().map(PosDraftLine::getItemName).collect(java.util.stream.Collectors.joining(", ")),
                    d.getBookingName(), d.getRequestedStaffProfileId(),
                    lines.stream().map(PosDraftLine::getResourceId).filter(java.util.Objects::nonNull).findFirst().orElse(null),
                    d.getScheduledStart(), d.getScheduledEnd()));
        }
        Instant now = Instant.now();
        Map<String, PosDraft> drafts = new java.util.HashMap<>();
        List<PosDraftLine> started = lineRepository.findStartedBetween(businessId, branch, from, to);
        Map<String, Integer> planned = serviceCatalogService.durationsByIds(businessId,
                started.stream().map(PosDraftLine::getServiceId).filter(java.util.Objects::nonNull).toList());
        for (PosDraftLine line : started) {
            PosDraft d = drafts.computeIfAbsent(line.getDraftId(),
                    id -> draftRepository.findByIdAndBusinessId(id, businessId).orElse(null));
            if (d == null || PosDraftConstants.STATUS_CANCELLED.equals(d.getStatus())) {
                continue;
            }
            boolean running = PosDraftConstants.STAGE_IN_SERVICE.equals(line.getLineStage());
            Instant end = line.getFinishedAt() != null ? line.getFinishedAt()
                    : line.getStartedAt().plus(Math.max(planned.getOrDefault(line.getServiceId(), 0), 5),
                            java.time.temporal.ChronoUnit.MINUTES);
            if (running && end.isBefore(now)) {
                // Running over: it holds its person and place until someone marks it done.
                end = now;
            }
            entries.add(new zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CalendarEntry(
                    running ? "IN_SERVICE" : "DONE", d.getId(), d.getTicketNumber(), line.getItemName(),
                    d.getBookingName(), line.getPerformerStaffProfileId(), line.getResourceId(),
                    line.getStartedAt(), end));
        }
        Map<String, String> statuses = staffProfileService.floorStatuses(businessId);
        Map<String, String> names = staffProfileService.displayNamesByIds(businessId, statuses.keySet());
        List<zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CalendarColumn> staff = new ArrayList<>();
        for (String id : statuses.keySet()) {
            staff.add(new zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CalendarColumn(id, names.getOrDefault(id, "Staff")));
        }
        for (var off : staffProfileService.timeOff(businessId).entrySet()) {
            for (var period : off.getValue()) {
                if (period.overlaps(from, to)) {
                    entries.add(new zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CalendarEntry(
                            "TIME_OFF", null, null, period.reason() == null || period.reason().isBlank() ? "Off" : period.reason(),
                            null, off.getKey(), null, period.from(), period.to()));
                }
            }
        }
        List<zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CalendarColumn> places = new ArrayList<>();
        for (var resource : resources) {
            if (!resource.active()) {
                continue;
            }
            places.add(new zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CalendarColumn(resource.id(), resource.name()));
            if (resource.blockedUntil() != null && resource.blockedUntil().isAfter(from)) {
                entries.add(new zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CalendarEntry(
                        "BLOCKED", null, null,
                        resource.blockedReason() == null ? "Out of use" : resource.blockedReason(), null, null,
                        resource.id(), now.isAfter(from) ? now : from, resource.blockedUntil()));
            }
        }
        entries.sort(java.util.Comparator.comparing(zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CalendarEntry::start));
        return new zelisline.ub.posdraft.api.dto.ServiceTicketDtos.CalendarResponse(branch, from, to, staff, places, entries);
    }

    private void requireFree(String businessId, String staffId, Instant start, Instant end, String exceptTicketId) {
        if (staffProfileService.isOff(businessId, staffId, start, end)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "They are off at that time");
        }
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

    /** The services of a visit. Products bought on it are {@link #productLines}. */
    private List<PosDraftLine> activeLines(String ticketId) {
        return lineRepository.findByDraftIdOrderByLineIndexAsc(ticketId).stream()
                .filter(line -> !line.isDeleted() && SaleLineKinds.isService(line.getLineKind()))
                .toList();
    }

    private List<PosDraftLine> productLines(String ticketId) {
        return lineRepository.findByDraftIdOrderByLineIndexAsc(ticketId).stream()
                .filter(line -> !line.isDeleted() && !SaleLineKinds.isService(line.getLineKind()))
                .toList();
    }

    /** The visit's total is its services plus its products, read back from what is stored. */
    private void recomputeTotal(PosDraft ticket, List<PosDraftLine> ignored) {
        BigDecimal total = BigDecimal.ZERO;
        for (PosDraftLine line : lineRepository.findByDraftIdOrderByLineIndexAsc(ticket.getId())) {
            if (!line.isDeleted()) {
                total = total.add(line.getLineTotal());
            }
        }
        total = total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        ticket.setSubTotal(total);
        ticket.setGrandTotal(total);
    }

    /** Names of everyone on these visits: who was asked for, who does each service, who helps. */
    private Map<String, String> staffNames(String businessId, List<PosDraft> tickets) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (PosDraft ticket : tickets) {
            if (ticket.getRequestedStaffProfileId() != null && !ticket.getRequestedStaffProfileId().isBlank()) {
                ids.add(ticket.getRequestedStaffProfileId());
            }
            for (PosDraftLine line : activeLines(ticket.getId())) {
                if (line.getPerformerStaffProfileId() != null) {
                    ids.add(line.getPerformerStaffProfileId());
                }
                for (var helper : zelisline.ub.serviceops.domain.ServiceHelpers.decode(line.getServiceHelpers())) {
                    ids.add(helper.staffProfileId());
                }
            }
        }
        return ids.isEmpty() ? Map.of() : staffProfileService.displayNamesByIds(businessId, ids);
    }

    private TicketResponse toResponse(PosDraft ticket, List<PosDraftLine> lines, Map<String, String> names) {
        return toResponse(ticket, lines, names, null);
    }

    private TicketResponse toResponse(
            PosDraft ticket, List<PosDraftLine> lines, Map<String, String> names, Integer estimatedWait) {
        List<TicketLineResponse> lineDtos = new ArrayList<>(lines.size());
        Map<String, String> places = resourceService.namesByIds(ticket.getBusinessId(),
                lines.stream().map(PosDraftLine::getResourceId).filter(java.util.Objects::nonNull).toList());
        Map<String, Integer> planned = serviceCatalogService.durationsByIds(ticket.getBusinessId(),
                lines.stream().map(PosDraftLine::getServiceId).filter(java.util.Objects::nonNull).toList());
        for (PosDraftLine line : lines) {
            if (!line.isDeleted()) {
                lineDtos.add(new TicketLineResponse(
                        line.getId(), line.getServiceId(), line.getItemName(), line.getUnitPrice(), line.getLineTotal(),
                        zelisline.ub.serviceops.domain.ServiceHelpers.decode(line.getServiceHelpers()).stream()
                                .map(h -> new zelisline.ub.posdraft.api.dto.ServiceTicketDtos.LineHelper(
                                        h.staffProfileId(), h.sharePercent(), h.amount()))
                                .toList(),
                        line.getPerformerStaffProfileId(),
                        line.getPerformerStaffProfileId() == null ? null : names.get(line.getPerformerStaffProfileId()),
                        line.getResourceId(),
                        line.getResourceId() == null ? null : places.get(line.getResourceId()),
                        line.getLineStage() == null ? PosDraftConstants.STAGE_WAITING : line.getLineStage(),
                        line.getStartedAt(),
                        line.getFinishedAt(),
                        planned.getOrDefault(line.getServiceId(), 0),
                        line.getPerformerAmount()));
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
                ticket.isSendReminder(),
                ticket.getRedeemedPackageId(),
                productLines(ticket.getId()).stream()
                        .map(p -> new zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TicketProductResponse(
                                p.getId(), p.getItemId(), p.getItemName(), p.getQuantity(), p.getUnitPrice(), p.getLineTotal()))
                        .toList());
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
