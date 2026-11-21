package zelisline.ub.restaurant.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.realtime.RealtimeBridge;
import zelisline.ub.posdraft.application.OrderTicketService;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.KitchenBoardResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.KitchenLineResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.KitchenTicketResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.OrderListResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.OrderSummaryResponse;
import zelisline.ub.restaurant.domain.KitchenTicket;
import zelisline.ub.restaurant.domain.KitchenTicketLine;
import zelisline.ub.restaurant.repository.KitchenTicketLineRepository;
import zelisline.ub.restaurant.repository.KitchenTicketRepository;

/**
 * The kitchen's side of an order: what each station has to make, marking it ready, and the list
 * of orders the counter can call. Progress is kept on the order's lines (ADR 0020); a ticket's
 * own status is worked out from them.
 */
@Service
@RequiredArgsConstructor
public class KitchenService {

    /** Station filter meaning the implicit kitchen: tickets with no station. */
    public static final String STATION_NONE = "none";

    /** A kitchen screen shows at most this many tickets; older ones stay first. */
    static final int MAX_BOARD_TICKETS = 200;

    private final KitchenTicketRepository ticketRepository;
    private final KitchenTicketLineRepository ticketLineRepository;
    private final OrderTicketService orderTicketService;
    private final RestaurantGate gate;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Tickets still being made at a branch, oldest first, and voids nobody has acknowledged.
     *
     * @param stationId a station id, {@link #STATION_NONE} for the implicit kitchen, or null for every station
     */
    @Transactional(readOnly = true)
    public KitchenBoardResponse board(String businessId, String branchId, String stationId) {
        gate.requireEnabled(businessId);
        List<KitchenTicket> tickets = ticketRepository
                .findBoard(businessId, branchId, PageRequest.of(0, MAX_BOARD_TICKETS)).stream()
                .filter(t -> matchesStation(t, stationId))
                .toList();
        return new KitchenBoardResponse(branchId, Instant.now(), toResponses(businessId, tickets));
    }

    /** Which branch a ticket belongs to, so the caller's branch can be checked before it is changed. */
    @Transactional(readOnly = true)
    public String branchOfTicket(String businessId, String ticketId) {
        return requireTicket(businessId, ticketId).getBranchId();
    }

    @Transactional(readOnly = true)
    public String branchOfLine(String businessId, String ticketLineId) {
        return requireTicket(businessId, requireLine(businessId, ticketLineId).getTicketId()).getBranchId();
    }

    @Transactional(readOnly = true)
    public String branchOfOrder(String businessId, String orderId) {
        return orderTicketService.find(businessId, orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"))
                .branchId();
    }

    /** Moves one line of a ticket to a stage. Repeating the same request changes nothing. */
    @Transactional
    public KitchenTicketResponse setLineStage(String businessId, String ticketLineId, String stage) {
        gate.requireEnabled(businessId);
        KitchenTicketLine line = requireLine(businessId, ticketLineId);
        KitchenTicket ticket = lockTicket(businessId, line.getTicketId());
        requireLiveTicket(ticket);
        Instant now = Instant.now();
        orderTicketService.setLineStage(businessId, line.getOrderLineId(), normalizeStage(stage), now);
        return refresh(businessId, ticket, now);
    }

    /**
     * Moves every line of a ticket forward to a stage. Lines already at or past it, and cancelled
     * lines, are left alone, so "all ready" after one plate was marked by hand is safe.
     */
    @Transactional
    public KitchenTicketResponse setTicketStage(String businessId, String ticketId, String stage) {
        gate.requireEnabled(businessId);
        KitchenTicket ticket = lockTicket(businessId, ticketId);
        requireLiveTicket(ticket);
        String target = normalizeStage(stage);
        Instant now = Instant.now();
        List<KitchenTicketLine> lines = ticketLineRepository
                .findByBusinessIdAndTicketIdInOrderByLineIndexAsc(businessId, List.of(ticket.getId()));
        Map<String, OrderTicketService.OrderLine> current = orderTicketService.linesById(
                businessId, lines.stream().map(KitchenTicketLine::getOrderLineId).toList());
        for (KitchenTicketLine line : lines) {
            OrderTicketService.OrderLine orderLine = current.get(line.getOrderLineId());
            if (orderLine == null || OrderTicketService.STAGE_VOIDED.equals(orderLine.stage())) {
                continue;
            }
            if (stageIndex(orderLine.stage()) < stageIndex(target)) {
                orderTicketService.setLineStage(businessId, line.getOrderLineId(), target, now);
            }
        }
        return refresh(businessId, ticket, now);
    }

    /** Someone in the kitchen has seen a void ticket; it leaves the board. */
    @Transactional
    public void acknowledgeVoid(String businessId, String ticketId) {
        gate.requireEnabled(businessId);
        KitchenTicket ticket = requireTicket(businessId, ticketId);
        if (!RestaurantSaleSupport.TICKET_VOID.equals(ticket.getTicketType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a cancellation ticket is acknowledged");
        }
        if (ticket.getAcknowledgedAt() == null) {
            ticket.setAcknowledgedAt(Instant.now());
            ticketRepository.save(ticket);
            eventPublisher.publishEvent(new RealtimeBridge.KitchenUpdatedEvent(businessId, ticket.getBranchId()));
        }
    }

    /** Orders paid for and not yet handed over, oldest first, each saying whether it is ready to call. */
    @Transactional(readOnly = true)
    public OrderListResponse ordersInProgress(String businessId, String branchId) {
        gate.requireEnabled(businessId);
        List<KitchenTicket> tickets =
                ticketRepository.findNotHandedOver(businessId, branchId, PageRequest.of(0, MAX_BOARD_TICKETS));
        Map<String, List<KitchenTicket>> byOrder = new LinkedHashMap<>();
        Map<String, Boolean> atCounter = new HashMap<>();
        for (KitchenTicket ticket : tickets) {
            // Only orders collected at the counter are called by number; a table's order is carried
            // to it by whoever is serving, and shows on the floor instead.
            boolean counter = atCounter.computeIfAbsent(ticket.getOrderId(), id -> orderTicketService
                    .find(businessId, id)
                    .map(o -> OrderTicketService.CHANNEL_COUNTER.equals(o.channel()))
                    .orElse(false));
            if (counter) {
                byOrder.computeIfAbsent(ticket.getOrderId(), k -> new ArrayList<>()).add(ticket);
            }
        }
        List<OrderSummaryResponse> orders = new ArrayList<>();
        for (List<KitchenTicket> group : byOrder.values()) {
            orders.add(summarize(businessId, group));
        }
        return new OrderListResponse(branchId, Instant.now(), orders);
    }

    /** The order a sale produced, for the counter to show and print its number. Empty when the sale sent nothing to a kitchen. */
    @Transactional(readOnly = true)
    public java.util.Optional<OrderSummaryResponse> orderForSale(String businessId, String saleId) {
        gate.requireEnabled(businessId);
        List<KitchenTicket> tickets = ticketRepository.findByBusinessIdAndSaleId(businessId, saleId).stream()
                .filter(t -> RestaurantSaleSupport.TICKET_NEW.equals(t.getTicketType()))
                .toList();
        return tickets.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(summarize(businessId, tickets));
    }

    /**
     * The customer has collected the order. Refused while the kitchen is still making part of it,
     * so an order is never closed with a plate outstanding.
     */
    @Transactional
    public OrderSummaryResponse handOver(String businessId, String orderId) {
        gate.requireEnabled(businessId);
        List<KitchenTicket> tickets = ticketRepository.findByBusinessIdAndOrderId(businessId, orderId).stream()
                .filter(t -> RestaurantSaleSupport.TICKET_NEW.equals(t.getTicketType()))
                .toList();
        if (tickets.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        if (tickets.stream().anyMatch(t -> RestaurantSaleSupport.STATUS_OPEN.equals(t.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The kitchen has not finished this order");
        }
        Instant now = Instant.now();
        for (KitchenTicket unlocked : tickets) {
            KitchenTicket ticket = lockTicket(businessId, unlocked.getId());
            if (RestaurantSaleSupport.STATUS_READY.equals(ticket.getStatus())) {
                for (KitchenTicketLine line : ticketLineRepository
                        .findByBusinessIdAndTicketIdInOrderByLineIndexAsc(businessId, List.of(ticket.getId()))) {
                    OrderTicketService.OrderLine orderLine = orderTicketService
                            .linesById(businessId, List.of(line.getOrderLineId())).get(line.getOrderLineId());
                    if (orderLine != null && OrderTicketService.STAGE_READY.equals(orderLine.stage())) {
                        orderTicketService.setLineStage(
                                businessId, line.getOrderLineId(), OrderTicketService.STAGE_SERVED, now);
                    }
                }
                refresh(businessId, ticket, now);
            }
        }
        return summarize(businessId, ticketRepository.findByBusinessIdAndOrderId(businessId, orderId).stream()
                .filter(t -> RestaurantSaleSupport.TICKET_NEW.equals(t.getTicketType())).toList());
    }

    // ------------------------------------------------------------------ internals

    private KitchenTicketResponse refresh(String businessId, KitchenTicket ticket, Instant now) {
        List<KitchenTicketLine> lines = ticketLineRepository
                .findByBusinessIdAndTicketIdInOrderByLineIndexAsc(businessId, List.of(ticket.getId()));
        Map<String, OrderTicketService.OrderLine> current = orderTicketService.linesById(
                businessId, lines.stream().map(KitchenTicketLine::getOrderLineId).toList());
        RestaurantSaleSupport.applyStatus(ticket,
                lines.stream().map(l -> current.get(l.getOrderLineId())).filter(Objects::nonNull)
                        .map(OrderTicketService.OrderLine::stage).toList(),
                now);
        ticketRepository.save(ticket);
        eventPublisher.publishEvent(new RealtimeBridge.KitchenUpdatedEvent(businessId, ticket.getBranchId()));
        return toResponse(ticket, lines, current);
    }

    private OrderSummaryResponse summarize(String businessId, List<KitchenTicket> tickets) {
        KitchenTicket first = tickets.get(0);
        List<KitchenTicketLine> lines = ticketLineRepository.findByBusinessIdAndTicketIdInOrderByLineIndexAsc(
                businessId, tickets.stream().map(KitchenTicket::getId).toList());
        Map<String, OrderTicketService.OrderLine> current = orderTicketService.linesById(
                businessId, lines.stream().map(KitchenTicketLine::getOrderLineId).toList());
        int total = 0;
        int ready = 0;
        List<String> names = new ArrayList<>();
        for (KitchenTicketLine line : lines) {
            OrderTicketService.OrderLine orderLine = current.get(line.getOrderLineId());
            if (orderLine == null || OrderTicketService.STAGE_VOIDED.equals(orderLine.stage())) {
                continue;
            }
            total++;
            if (stageIndex(orderLine.stage()) >= stageIndex(OrderTicketService.STAGE_READY)) {
                ready++;
            }
            names.add(line.getQuantity().stripTrailingZeros().toPlainString() + " × " + line.getName());
        }
        Instant sentAt = tickets.stream().map(KitchenTicket::getSentAt).min(Instant::compareTo).orElse(null);
        // The name the counter calls this order by, so a takeaway can be called "Kevin" and not "34".
        String customerName = orderTicketService.find(businessId, first.getOrderId())
                .map(OrderTicketService.Order::customerName).orElse(null);
        return new OrderSummaryResponse(first.getOrderId(), first.getOrderNumber(), first.getSaleId(), sentAt,
                total > 0 && ready == total, total, ready, names, customerName);
    }

    private List<KitchenTicketResponse> toResponses(String businessId, List<KitchenTicket> tickets) {
        if (tickets.isEmpty()) {
            return List.of();
        }
        Map<String, List<KitchenTicketLine>> linesByTicket = new HashMap<>();
        List<KitchenTicketLine> all = ticketLineRepository.findByBusinessIdAndTicketIdInOrderByLineIndexAsc(
                businessId, tickets.stream().map(KitchenTicket::getId).toList());
        for (KitchenTicketLine line : all) {
            linesByTicket.computeIfAbsent(line.getTicketId(), k -> new ArrayList<>()).add(line);
        }
        Map<String, OrderTicketService.OrderLine> current = orderTicketService.linesById(
                businessId, all.stream().map(KitchenTicketLine::getOrderLineId).distinct().toList());
        return tickets.stream()
                .map(t -> toResponse(t, linesByTicket.getOrDefault(t.getId(), List.of()), current))
                .toList();
    }

    private static KitchenTicketResponse toResponse(
            KitchenTicket ticket, List<KitchenTicketLine> lines, Map<String, OrderTicketService.OrderLine> current) {
        boolean isVoid = RestaurantSaleSupport.TICKET_VOID.equals(ticket.getTicketType());
        List<KitchenLineResponse> out = new ArrayList<>();
        for (KitchenTicketLine line : lines) {
            OrderTicketService.OrderLine orderLine = current.get(line.getOrderLineId());
            String stage = isVoid || orderLine == null ? OrderTicketService.STAGE_VOIDED : orderLine.stage();
            out.add(new KitchenLineResponse(line.getId(), line.getOrderLineId(), line.getName(), line.getQuantity(),
                    ModifierSnapshots.decode(line.getModifiers()), line.getNote(), stage,
                    orderLine == null ? null : orderLine.readyAt(),
                    line.getSeatNo() != null ? line.getSeatNo() : (orderLine == null ? null : orderLine.seatNo())));
        }
        return new KitchenTicketResponse(ticket.getId(), ticket.getOrderId(), ticket.getOrderNumber(),
                ticket.getStationId(), ticket.getTicketType(), ticket.getStatus(), ticket.getRoundNo(),
                ticket.getSentAt(), ticket.getReadyAt(), out, ticket.getOrderChannel(), ticket.getTableName(),
                ticket.getCustomerName());
    }

    private static boolean matchesStation(KitchenTicket ticket, String stationId) {
        if (stationId == null || stationId.isBlank()) {
            return true;
        }
        if (STATION_NONE.equalsIgnoreCase(stationId.trim())) {
            return ticket.getStationId() == null;
        }
        return stationId.trim().equals(ticket.getStationId());
    }

    private static void requireLiveTicket(KitchenTicket ticket) {
        if (!RestaurantSaleSupport.TICKET_NEW.equals(ticket.getTicketType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A cancellation ticket has nothing to prepare");
        }
    }

    private static String normalizeStage(String stage) {
        String s = stage == null ? "" : stage.trim().toUpperCase(java.util.Locale.ROOT);
        if (stageIndex(s) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown kitchen stage");
        }
        return s;
    }

    private static int stageIndex(String stage) {
        return List.of(OrderTicketService.STAGE_NEW, OrderTicketService.STAGE_PREPARING,
                OrderTicketService.STAGE_READY, OrderTicketService.STAGE_SERVED).indexOf(stage);
    }

    private KitchenTicket requireTicket(String businessId, String ticketId) {
        return ticketRepository.findByIdAndBusinessId(ticketId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kitchen ticket not found"));
    }

    private KitchenTicket lockTicket(String businessId, String ticketId) {
        return ticketRepository.findByIdAndBusinessIdForUpdate(ticketId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kitchen ticket not found"));
    }

    private KitchenTicketLine requireLine(String businessId, String ticketLineId) {
        return ticketLineRepository.findByIdAndBusinessId(ticketLineId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kitchen ticket line not found"));
    }
}
