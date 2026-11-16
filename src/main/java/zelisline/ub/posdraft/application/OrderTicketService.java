package zelisline.ub.posdraft.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.posdraft.PosDraftConstants;
import zelisline.ub.posdraft.domain.PosDraft;
import zelisline.ub.posdraft.domain.PosDraftLine;
import zelisline.ub.posdraft.infrastructure.BranchPosSequenceAllocator;
import zelisline.ub.posdraft.repository.PosDraftLineRepository;
import zelisline.ub.posdraft.repository.PosDraftRepository;

/**
 * Restaurant orders as held tickets of kind {@code ORDER} (ADR 0020). This is the only way the
 * restaurant module reaches {@code pos_drafts}: it never sees the held-cart or service-ticket
 * rows, and those services never see an order.
 *
 * <p>Two shapes of order live here. A <b>counter</b> order is paid before it is made, so it is
 * written already completed and linked to its sale. A <b>table, takeaway or delivery</b> order
 * is opened, added to, sent to the kitchen in rounds and paid later; it stays {@code pending}
 * until every line is settled.
 *
 * <p>A line is {@code DRAFT} until it is sent. Once sent it is never edited: it moves through the
 * kitchen stages or is cancelled with a reason. Kitchen progress is changed without touching the
 * order row, so two cooks marking different plates never collide on its version; anything that
 * changes what is on the order takes the order's row lock first.
 */
@Service
@RequiredArgsConstructor
public class OrderTicketService {

    public static final String CHANNEL_COUNTER = "COUNTER";
    public static final String CHANNEL_DINE_IN = "DINE_IN";
    public static final String CHANNEL_TAKEAWAY = "TAKEAWAY";
    public static final String CHANNEL_DELIVERY = "DELIVERY";

    /** On the order, not yet sent to the kitchen; still freely editable. */
    public static final String STAGE_DRAFT = "DRAFT";
    public static final String STAGE_NEW = "NEW";
    public static final String STAGE_PREPARING = "PREPARING";
    public static final String STAGE_READY = "READY";
    public static final String STAGE_SERVED = "SERVED";
    public static final String STAGE_VOIDED = "VOIDED";

    /** A table booked for later: an order that has not arrived yet. */
    public static final String ORDER_BOOKED = PosDraftConstants.STAGE_BOOKED;

    private static final List<String> STAGE_ORDER = List.of(STAGE_NEW, STAGE_PREPARING, STAGE_READY, STAGE_SERVED);
    private static final Set<String> OPEN_CHANNELS = Set.of(CHANNEL_DINE_IN, CHANNEL_TAKEAWAY, CHANNEL_DELIVERY);

    /** An order is read whole by a phone, so its size is bounded. */
    public static final int MAX_LINES_PER_ORDER = 300;

    private final PosDraftRepository draftRepository;
    private final PosDraftLineRepository lineRepository;
    private final BranchPosSequenceAllocator sequenceAllocator;
    private final zelisline.ub.serviceops.application.HeldMoneyService heldMoneyService;

    /** A line of a new order. {@code toKitchen} false is something handed over at the counter (a bottled soda). */
    public record NewLine(
            String lineKind, String itemId, String menuItemId, String name, BigDecimal quantity,
            BigDecimal unitPrice, String modifiers, String note, String stationId, boolean toKitchen,
            Integer seatNo, String clientLineId) {

        public NewLine(
                String lineKind, String itemId, String menuItemId, String name, BigDecimal quantity,
                BigDecimal unitPrice, String modifiers, String note, String stationId, boolean toKitchen) {
            this(lineKind, itemId, menuItemId, name, quantity, unitPrice, modifiers, note, stationId, toKitchen,
                    null, null);
        }
    }

    /**
     * Start of the client line id of a line a customer added themselves from a table's QR code.
     * Staff must be able to tell those apart before sending them to the kitchen: anyone holding
     * a picture of the code can add them.
     */
    public static final String CUSTOMER_LINE_PREFIX = "qr-";

    public record OrderLine(
            String id, String orderId, int lineIndex, String name, BigDecimal quantity, String modifiers,
            String note, String stationId, String stage, Instant sentAt, Instant readyAt, Instant servedAt,
            String lineKind, String itemId, String menuItemId, BigDecimal unitPrice, BigDecimal lineTotal,
            Integer seatNo, String voidReason, boolean fromCustomer) {

        public boolean isVoided() {
            return STAGE_VOIDED.equals(stage);
        }

        public boolean isDraft() {
            return STAGE_DRAFT.equals(stage);
        }
    }

    public record Order(
            String id, String branchId, long orderNumber, String saleId, String channel, Instant createdAt,
            List<OrderLine> lines, String status, String bookingStage, String tableResourceId, Integer guestCount,
            String servedByStaffProfileId, String customerId, String customerName, String customerPhone,
            String deliveryAddress, Instant billRequestedAt, Instant scheduledStart, Instant scheduledEnd,
            String createdBy, long version, BigDecimal deliveryFee, String riderName, Instant dispatchedAt,
            Instant deliveredAt, String source,
            BigDecimal depositAmount, String depositState, String depositMethod) {

        public boolean isOpen() {
            return PosDraftConstants.STATUS_PENDING.equals(status);
        }

        public boolean isBooked() {
            return ORDER_BOOKED.equals(bookingStage);
        }

        public boolean hasHeldDeposit() {
            return PosDraftConstants.DEPOSIT_HELD.equals(depositState);
        }
    }

    /** What is needed to open an order that will be paid later. */
    public record OpenOrder(
            String branchId, String channel, String tableResourceId, Integer guestCount,
            String servedByStaffProfileId, String customerId, String customerName, String customerPhone,
            String deliveryAddress, String clientOrderId, Instant scheduledStart, Instant scheduledEnd,
            BigDecimal deliveryFee, String source) {

        public OpenOrder(
                String branchId, String channel, String tableResourceId, Integer guestCount,
                String servedByStaffProfileId, String customerId, String customerName, String customerPhone,
                String deliveryAddress, String clientOrderId, Instant scheduledStart, Instant scheduledEnd) {
            this(branchId, channel, tableResourceId, guestCount, servedByStaffProfileId, customerId, customerName,
                    customerPhone, deliveryAddress, clientOrderId, scheduledStart, scheduledEnd, null, "STAFF");
        }
    }

    // ------------------------------------------------------------------ counter (paid first)

    /**
     * Writes the order for a sale that has just been paid, in the caller's transaction. Lines
     * that go to the kitchen start at {@code NEW} with their sent time; the rest carry no stage.
     */
    @Transactional
    public Order createPaidCounterOrder(
            String businessId, String branchId, String userId, String shiftId, String saleId,
            String customerId, List<NewLine> lines, Instant soldAt) {
        Optional<PosDraft> existing = draftRepository.findFirstByBusinessIdAndSaleIdAndDraftKind(
                businessId, saleId, PosDraftConstants.KIND_ORDER);
        if (existing.isPresent()) {
            return toOrder(existing.get());
        }
        Instant now = soldAt != null ? soldAt : Instant.now();
        PosDraft order = new PosDraft();
        order.setBusinessId(businessId);
        order.setBranchId(branchId);
        order.setTicketNumber(sequenceAllocator.allocateTicketNumber(branchId));
        order.setDraftKind(PosDraftConstants.KIND_ORDER);
        order.setOrderChannel(CHANNEL_COUNTER);
        order.setStatus(PosDraftConstants.STATUS_COMPLETED);
        order.setCreatedBy(userId);
        order.setShiftId(shiftId);
        order.setSaleId(saleId);
        order.setCustomerId(customerId);
        order.setClientDraftId("order-" + saleId);
        order.setCompletedAt(now);
        BigDecimal total = BigDecimal.ZERO;
        for (NewLine line : lines) {
            total = total.add(lineTotal(line.quantity(), line.unitPrice()));
        }
        order.setSubTotal(total);
        order.setGrandTotal(total);
        draftRepository.save(order);

        List<PosDraftLine> saved = new ArrayList<>();
        int index = 0;
        for (NewLine line : lines) {
            PosDraftLine row = newRow(businessId, order.getId(), index++, line);
            if (line.toKitchen()) {
                row.setLineStage(STAGE_NEW);
                row.setSentAt(now);
            }
            saved.add(row);
        }
        lineRepository.saveAll(saved);
        return toOrder(order, saved);
    }

    // ------------------------------------------------------------------ open orders (paid later)

    /**
     * Opens an order to be paid later. Repeating the request with the same {@code clientOrderId}
     * returns the order already opened, so a double tap or a retry never seats a table twice.
     */
    @Transactional
    public Order open(String businessId, String userId, OpenOrder request) {
        String channel = request.channel() == null ? "" : request.channel().trim().toUpperCase(java.util.Locale.ROOT);
        if (!OPEN_CHANNELS.contains(channel)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An order is for a table, to take away or to deliver");
        }
        String clientId = request.clientOrderId() == null || request.clientOrderId().isBlank()
                ? null : "order-" + request.clientOrderId().trim();
        if (clientId != null) {
            if (clientId.length() > 64) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That order reference is too long");
            }
            Optional<PosDraft> existing = draftRepository.findByBusinessIdAndClientDraftId(businessId, clientId);
            if (existing.isPresent()) {
                if (!PosDraftConstants.KIND_ORDER.equals(existing.get().getDraftKind())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "That reference is already used");
                }
                return toOrder(existing.get());
            }
        }
        // Only a table is booked. A time on a takeaway or delivery order is when it is wanted, and
        // the order is open straight away so the kitchen can work towards it.
        boolean booking = request.scheduledStart() != null && CHANNEL_DINE_IN.equals(channel);
        if (booking && (request.scheduledEnd() == null || !request.scheduledEnd().isAfter(request.scheduledStart()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A booking needs a start and a later end");
        }
        if (request.guestCount() != null && (request.guestCount() < 1 || request.guestCount() > 500)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check the number of guests");
        }
        PosDraft order = new PosDraft();
        order.setBusinessId(businessId);
        order.setBranchId(request.branchId());
        order.setTicketNumber(sequenceAllocator.allocateTicketNumber(request.branchId()));
        order.setDraftKind(PosDraftConstants.KIND_ORDER);
        order.setOrderChannel(channel);
        order.setStatus(PosDraftConstants.STATUS_PENDING);
        order.setCreatedBy(userId);
        order.setCustomerId(blankToNull(request.customerId()));
        order.setTableResourceId(blankToNull(request.tableResourceId()));
        order.setGuestCount(request.guestCount());
        order.setServedByStaffProfileId(blankToNull(request.servedByStaffProfileId()));
        order.setBookingName(trim(request.customerName(), 120));
        order.setBookingPhone(trim(request.customerPhone(), 32));
        order.setDeliveryAddress(trim(request.deliveryAddress(), 500));
        order.setClientDraftId(clientId);
        order.setBookingSource(request.source() == null ? "STAFF" : request.source());
        order.setDeliveryFee(request.deliveryFee());
        if (booking) {
            order.setServiceStage(ORDER_BOOKED);
            order.setScheduledStart(request.scheduledStart());
            order.setScheduledEnd(request.scheduledEnd());
        } else if (request.scheduledStart() != null) {
            order.setScheduledStart(request.scheduledStart());
            order.setScheduledEnd(request.scheduledEnd() != null ? request.scheduledEnd() : request.scheduledStart());
        }
        draftRepository.save(order);
        return toOrder(order, List.of());
    }

    /** Open orders at a branch, oldest first, without their lines' history trimmed: the floor reads this. */
    @Transactional(readOnly = true)
    public List<Order> openOrders(String businessId, String branchId) {
        List<Order> out = new ArrayList<>();
        for (PosDraft order : draftRepository.findByBusinessIdAndBranchIdAndDraftKindAndStatusOrderByCreatedAtAsc(
                businessId, branchId, PosDraftConstants.KIND_ORDER, PosDraftConstants.STATUS_PENDING)) {
            out.add(toOrder(order));
        }
        return out;
    }

    /**
     * Deliveries that are paid for but not yet delivered. An order paid when it was placed is
     * closed long before the rider arrives, and must stay in front of staff until then.
     */
    @Transactional(readOnly = true)
    public List<Order> settledDeliveriesNotDelivered(String businessId, String branchId) {
        Instant since = Instant.now().minus(java.time.Duration.ofHours(24));
        return draftRepository.findSettledDeliveriesNotDelivered(businessId, branchId, since).stream()
                .map(this::toOrder).toList();
    }

    /** Bookings at a branch that overlap a window. */
    @Transactional(readOnly = true)
    public List<Order> bookingsOverlapping(String businessId, String branchId, Instant from, Instant to) {
        return draftRepository.findOrderBookingsOverlapping(businessId, branchId, from, to).stream()
                .map(this::toOrder).toList();
    }

    /** Takes the order's row lock and returns it; everything that changes an order starts here. */
    @Transactional
    public Order lock(String businessId, String orderId) {
        return toOrder(lockRow(businessId, orderId));
    }

    /**
     * Adds lines to an open order as {@code DRAFT}. A line whose {@code clientLineId} is already
     * on the order is skipped, so a repeated request adds nothing twice.
     */
    @Transactional
    public Order addLines(String businessId, String orderId, List<NewLine> lines) {
        PosDraft order = requireOpen(lockRow(businessId, orderId));
        List<PosDraftLine> existing = lineRepository.findByDraftIdOrderByLineIndexAsc(order.getId());
        Set<String> seen = new HashSet<>();
        for (PosDraftLine row : existing) {
            if (row.getClientLineId() != null) {
                seen.add(row.getClientLineId());
            }
        }
        int index = existing.stream().mapToInt(PosDraftLine::getLineIndex).max().orElse(-1) + 1;
        List<PosDraftLine> added = new ArrayList<>();
        for (NewLine line : lines) {
            if (line.clientLineId() != null && !seen.add(line.clientLineId())) {
                continue;
            }
            if (existing.size() + added.size() >= MAX_LINES_PER_ORDER) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This order has too many lines; open another");
            }
            PosDraftLine row = newRow(businessId, order.getId(), index++, line);
            row.setLineStage(STAGE_DRAFT);
            added.add(row);
        }
        lineRepository.saveAll(added);
        return touch(order);
    }

    /** Changes the quantity, note or seat of a line that has not been sent. Zero removes it. */
    @Transactional
    public Order changeDraftLine(
            String businessId, String orderId, String lineId, BigDecimal quantity, String note, Integer seatNo) {
        PosDraft order = requireOpen(lockRow(businessId, orderId));
        PosDraftLine row = requireLine(order, lineId);
        if (!STAGE_DRAFT.equals(row.getLineStage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That has gone to the kitchen; cancel it instead of changing it");
        }
        if (quantity != null) {
            if (quantity.signum() <= 0) {
                lineRepository.delete(row);
                return touch(order);
            }
            if (quantity.stripTrailingZeros().scale() > 0 && "DISH".equals(row.getLineKind())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A dish is ordered in whole numbers");
            }
            row.setQuantity(quantity.setScale(PosDraftConstants.QTY_SCALE, RoundingMode.HALF_UP));
            row.setLineTotal(lineTotal(row.getQuantity(), row.getUnitPrice()));
        }
        if (note != null) {
            row.setKitchenNote(cleanNote(note));
        }
        if (seatNo != null) {
            row.setSeatNo(seatNo <= 0 ? null : seatNo);
        }
        lineRepository.save(row);
        return touch(order);
    }

    /**
     * Sends every unsent line. Lines with a kitchen become {@code NEW}; the rest need no making and
     * become {@code READY} for the waiter. Returns the lines just sent — empty when there was
     * nothing to send, which makes a second tap harmless.
     */
    @Transactional
    public List<OrderLine> send(String businessId, String orderId, Instant now) {
        PosDraft order = requireOpen(lockRow(businessId, orderId));
        if (ORDER_BOOKED.equals(order.getServiceStage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat the booking before sending its order");
        }
        List<OrderLine> sent = new ArrayList<>();
        for (PosDraftLine row : lineRepository.findByDraftIdOrderByLineIndexAsc(order.getId())) {
            if (!STAGE_DRAFT.equals(row.getLineStage())) {
                continue;
            }
            // A dish is always made; a product only when a station was named for it.
            boolean toKitchen = "DISH".equals(row.getLineKind()) || row.getStationId() != null;
            row.setSentAt(now);
            if (toKitchen) {
                row.setLineStage(STAGE_NEW);
            } else {
                row.setLineStage(STAGE_READY);
                row.setReadyAt(now);
            }
            lineRepository.save(row);
            sent.add(toLine(row));
        }
        if (!sent.isEmpty()) {
            touch(order);
        }
        return sent;
    }

    /**
     * Cancels a line. An unsent line simply goes. A sent one is kept, marked {@code VOIDED} with
     * the reason and who did it; the returned flag says whether the kitchen was still making it.
     */
    @Transactional
    public VoidedLine voidLine(String businessId, String orderId, String lineId, String reason, String userId) {
        PosDraft order = requireOpen(lockRow(businessId, orderId));
        PosDraftLine row = requireLine(order, lineId);
        if (STAGE_VOIDED.equals(row.getLineStage())) {
            return new VoidedLine(toLine(row), false, true);
        }
        if (STAGE_DRAFT.equals(row.getLineStage())) {
            OrderLine gone = toLine(row);
            lineRepository.delete(row);
            touch(order);
            return new VoidedLine(gone, false, false);
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why it is being cancelled");
        }
        boolean beingMade = STAGE_NEW.equals(row.getLineStage()) || STAGE_PREPARING.equals(row.getLineStage());
        row.setLineStage(STAGE_VOIDED);
        row.setVoidReason(trim(reason, 255));
        row.setVoidedBy(userId);
        lineRepository.save(row);
        touch(order);
        return new VoidedLine(toLine(row), beingMade, true);
    }

    /** @param wasSent false when the line had never reached the kitchen and was simply removed */
    public record VoidedLine(OrderLine line, boolean kitchenWasMakingIt, boolean wasSent) {
    }

    /** Moves an order to another table, or to none. The caller has checked the table is free. */
    @Transactional
    public Order moveToTable(String businessId, String orderId, String tableResourceId) {
        PosDraft order = requireOpen(lockRow(businessId, orderId));
        order.setTableResourceId(blankToNull(tableResourceId));
        return touch(order);
    }

    @Transactional
    public Order setDetails(
            String businessId, String orderId, Integer guestCount, String servedByStaffProfileId,
            String customerId, String customerName, String customerPhone, String deliveryAddress) {
        PosDraft order = requireOpen(lockRow(businessId, orderId));
        if (guestCount != null) {
            if (guestCount < 1 || guestCount > 500) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check the number of guests");
            }
            order.setGuestCount(guestCount);
        }
        if (servedByStaffProfileId != null) {
            order.setServedByStaffProfileId(blankToNull(servedByStaffProfileId));
        }
        if (customerId != null) {
            order.setCustomerId(blankToNull(customerId));
        }
        if (customerName != null) {
            order.setBookingName(trim(customerName, 120));
        }
        if (customerPhone != null) {
            order.setBookingPhone(trim(customerPhone, 32));
        }
        if (deliveryAddress != null) {
            order.setDeliveryAddress(trim(deliveryAddress, 500));
        }
        return touch(order);
    }

    /**
     * A delivery order's fee and rider, and its progress. Null leaves a field as it is.
     *
     * @param dispatched true stamps the time it left; false clears it and the delivered time
     * @param delivered true stamps the time it arrived (and the time it left, if that was never recorded)
     */
    @Transactional
    public Order setDelivery(
            String businessId, String orderId, BigDecimal deliveryFee, String riderName, Boolean dispatched,
            Boolean delivered, Instant now) {
        PosDraft order = lockRow(businessId, orderId);
        if (!CHANNEL_DELIVERY.equals(order.getOrderChannel())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This is not a delivery order");
        }
        if (deliveryFee != null) {
            requireOpen(order);
            order.setDeliveryFee(deliveryFee.signum() < 0 ? null : deliveryFee.setScale(2, RoundingMode.HALF_UP));
        }
        if (riderName != null) {
            order.setRiderName(trim(riderName, 120));
        }
        if (dispatched != null) {
            order.setDispatchedAt(dispatched ? (order.getDispatchedAt() == null ? now : order.getDispatchedAt()) : null);
            if (!dispatched) {
                order.setDeliveredAt(null);
            }
        }
        if (delivered != null) {
            if (delivered && order.getDispatchedAt() == null) {
                order.setDispatchedAt(now);
            }
            order.setDeliveredAt(delivered ? (order.getDeliveredAt() == null ? now : order.getDeliveredAt()) : null);
        }
        return touch(order);
    }

    /**
     * Joins one open order into another at the same branch: every line moves across, keeping its
     * kitchen stage, and the emptied order is closed with a note saying where it went. Both rows
     * are locked, lower id first, so two devices joining the same pair cannot deadlock.
     */
    @Transactional
    public Order merge(String businessId, String fromOrderId, String intoOrderId, String userId, Instant now) {
        if (fromOrderId.equals(intoOrderId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a different order to join with");
        }
        boolean fromFirst = fromOrderId.compareTo(intoOrderId) < 0;
        PosDraft first = lockRow(businessId, fromFirst ? fromOrderId : intoOrderId);
        PosDraft second = lockRow(businessId, fromFirst ? intoOrderId : fromOrderId);
        PosDraft from = requireOpen(fromFirst ? first : second);
        PosDraft into = requireOpen(fromFirst ? second : first);
        if (!from.getBranchId().equals(into.getBranchId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Those orders are at different branches");
        }
        if (ORDER_BOOKED.equals(from.getServiceStage()) || ORDER_BOOKED.equals(into.getServiceStage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat the booking before joining it with another order");
        }
        List<PosDraftLine> existing = lineRepository.findByDraftIdOrderByLineIndexAsc(into.getId());
        List<PosDraftLine> moving = lineRepository.findByDraftIdOrderByLineIndexAsc(from.getId());
        if (existing.size() + moving.size() > MAX_LINES_PER_ORDER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Together those orders have too many lines");
        }
        int index = existing.stream().mapToInt(PosDraftLine::getLineIndex).max().orElse(-1) + 1;
        for (PosDraftLine row : moving) {
            row.setDraftId(into.getId());
            row.setLineIndex(index++);
            // Its id on the old order must not block the same device adding a line to the new one.
            row.setClientLineId(null);
        }
        lineRepository.saveAll(moving);
        lineRepository.flush();
        if (into.getGuestCount() != null || from.getGuestCount() != null) {
            into.setGuestCount((into.getGuestCount() == null ? 0 : into.getGuestCount())
                    + (from.getGuestCount() == null ? 0 : from.getGuestCount()));
        }
        from.setStatus(PosDraftConstants.STATUS_CANCELLED);
        from.setCancelledAt(now);
        from.setCancelledBy(userId);
        from.setCancelledReason("Joined with order " + into.getTicketNumber());
        touch(from);
        return touch(into);
    }

    /** The customer has asked for the bill (true) or changed their mind (false). */
    @Transactional
    public Order requestBill(String businessId, String orderId, boolean requested, Instant now) {
        PosDraft order = requireOpen(lockRow(businessId, orderId));
        order.setBillRequestedAt(requested ? (order.getBillRequestedAt() == null ? now : order.getBillRequestedAt()) : null);
        return touch(order);
    }

    /** A booking's guests have arrived: it becomes an ordinary open order. */
    @Transactional
    public Order seatBooking(String businessId, String orderId) {
        PosDraft order = requireOpen(lockRow(businessId, orderId));
        if (ORDER_BOOKED.equals(order.getServiceStage())) {
            order.setServiceStage(null);
            return touch(order);
        }
        return toOrder(order);
    }

    /** Every line is settled: the order is finished and its table is free. Repeating it changes nothing. */
    @Transactional
    public Order complete(String businessId, String orderId, String lastSaleId, String shiftId, Instant now) {
        PosDraft order = lockRow(businessId, orderId);
        if (!order.isPending()) {
            return toOrder(order);
        }
        order.setStatus(PosDraftConstants.STATUS_COMPLETED);
        order.setCompletedAt(now);
        if (lastSaleId != null) {
            order.setSaleId(lastSaleId);
        }
        if (shiftId != null) {
            order.setShiftId(shiftId);
        }
        return touch(order);
    }

    /** A paid bill's sale was voided: the order has something unsettled again. */
    @Transactional
    public Order reopen(String businessId, String orderId) {
        PosDraft order = lockRow(businessId, orderId);
        if (PosDraftConstants.STATUS_COMPLETED.equals(order.getStatus()) && !CHANNEL_COUNTER.equals(order.getOrderChannel())) {
            order.setStatus(PosDraftConstants.STATUS_PENDING);
            order.setCompletedAt(null);
            return touch(order);
        }
        return toOrder(order);
    }

    /**
     * Cancels an order nothing was made for: a booking that will not come, or a table that sat
     * down and left. Refused once anything has been sent and not cancelled. A held deposit goes
     * back to the customer — handed back in cash or by M-Pesa when {@code refundsDeposit} is set,
     * otherwise kept as credit in their wallet.
     */
    @Transactional
    public Order cancel(String businessId, String orderId, String reason, String userId, Instant now,
            boolean refundsDeposit, String depositBack, String tillDeviceKey) {
        PosDraft order = requireOpen(lockRow(businessId, orderId));
        for (PosDraftLine row : lineRepository.findByDraftIdOrderByLineIndexAsc(order.getId())) {
            if (!STAGE_DRAFT.equals(row.getLineStage()) && !STAGE_VOIDED.equals(row.getLineStage())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Food has been sent for this order; cancel those lines or settle the bill");
            }
        }
        order.setStatus(PosDraftConstants.STATUS_CANCELLED);
        order.setCancelledAt(now);
        order.setCancelledBy(userId);
        order.setCancelledReason(trim(reason, 255));
        if (PosDraftConstants.DEPOSIT_HELD.equals(order.getDepositState())) {
            if (refundsDeposit) {
                heldMoneyService.refund(businessId, order.getBranchId(),
                        zelisline.ub.finance.LedgerAccountCodes.CUSTOMER_DEPOSITS,
                        zelisline.ub.serviceops.application.HeldMoneyService.SOURCE_DEPOSIT_REFUNDED, order.getId(),
                        order.getDepositAmount(), depositBack, depositMemo("handed back", order), userId, tillDeviceKey);
                order.setDepositState(PosDraftConstants.DEPOSIT_REFUNDED);
            } else {
                releaseDepositToWallet(businessId, order);
            }
        }
        return touch(order);
    }

    /**
     * Keep an earlier call working: cancel with no deposit decision. A held deposit becomes the
     * customer's wallet credit, the safe default (nothing is handed back without a decision).
     */
    @Transactional
    public Order cancel(String businessId, String orderId, String reason, String userId, Instant now) {
        return cancel(businessId, orderId, reason, userId, now, false, null, null);
    }

    /**
     * Money taken in advance against a booking: cash goes into the drawer, M-Pesa into the clearing
     * account, both against the customer-deposits liability (ADR 0015). Held in the customer's name,
     * so the booking must name one. Taking it twice is refused; taking the same amount again is a
     * no-op, so a replayed request does not charge twice.
     */
    @Transactional
    public Order takeDeposit(String businessId, String orderId, BigDecimal amount, String method, String userId,
            String tillDeviceKey) {
        PosDraft order = requireOpen(lockRow(businessId, orderId));
        BigDecimal money = zelisline.ub.serviceops.application.HeldMoneyService.requireAmount(amount);
        if (order.getDepositState() != null) {
            if (PosDraftConstants.DEPOSIT_HELD.equals(order.getDepositState())
                    && money.compareTo(order.getDepositAmount()) == 0) {
                return toOrder(order); // the same deposit, asked for again
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking already has a deposit");
        }
        if (order.getCustomerId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A deposit is held in the customer's name; name the customer first");
        }
        String payMethod = method == null ? "" : method.trim().toLowerCase(java.util.Locale.ROOT);
        heldMoneyService.receive(businessId, order.getBranchId(),
                zelisline.ub.finance.LedgerAccountCodes.CUSTOMER_DEPOSITS,
                zelisline.ub.serviceops.application.HeldMoneyService.SOURCE_DEPOSIT_TAKEN, order.getId(), money,
                payMethod, depositMemo("taken", order), userId, tillDeviceKey);
        order.setDepositAmount(money);
        order.setDepositMethod(payMethod);
        order.setDepositState(PosDraftConstants.DEPOSIT_HELD);
        return touch(order);
    }

    /**
     * A booking whose guests never came. The table was held and lost, so the shop keeps the deposit
     * as income by default; or, if staff choose, it becomes the customer's wallet credit. Only a
     * booking that has not been seated can be a no-show.
     */
    @Transactional
    public Order noShow(String businessId, String orderId, String userId, boolean keepAsCredit, Instant now) {
        PosDraft order = requireOpen(lockRow(businessId, orderId));
        if (!ORDER_BOOKED.equals(order.getServiceStage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a booking whose guests have not arrived can be a no-show");
        }
        order.setStatus(PosDraftConstants.STATUS_CANCELLED);
        order.setCancelledAt(now);
        order.setCancelledBy(userId);
        order.setCancelledReason("Did not turn up");
        if (PosDraftConstants.DEPOSIT_HELD.equals(order.getDepositState())) {
            if (keepAsCredit) {
                releaseDepositToWallet(businessId, order);
            } else {
                heldMoneyService.forfeit(businessId, order.getBranchId(),
                        zelisline.ub.finance.LedgerAccountCodes.CUSTOMER_DEPOSITS,
                        zelisline.ub.serviceops.application.HeldMoneyService.SOURCE_DEPOSIT_FORFEITED, order.getId(),
                        order.getDepositAmount(), zelisline.ub.finance.LedgerAccountCodes.SALES_REVENUE,
                        depositMemo("kept after no-show", order));
                order.setDepositState(PosDraftConstants.DEPOSIT_FORFEITED);
            }
        }
        return touch(order);
    }

    /**
     * A held deposit becomes the customer's wallet credit, where the booking's bill (or the
     * customer, later) spends it. Does nothing when there is no held deposit. The caller saves
     * the row.
     */
    public void releaseDepositToWallet(String businessId, PosDraft order) {
        if (!PosDraftConstants.DEPOSIT_HELD.equals(order.getDepositState())) {
            return;
        }
        heldMoneyService.releaseToWallet(businessId, order.getBranchId(),
                zelisline.ub.finance.LedgerAccountCodes.CUSTOMER_DEPOSITS,
                zelisline.ub.serviceops.application.HeldMoneyService.SOURCE_DEPOSIT_RELEASED, order.getId(),
                order.getCustomerId(), order.getDepositAmount(),
                zelisline.ub.credits.WalletTxnTypes.CREDIT_DEPOSIT_RELEASE, depositMemo("released", order));
        order.setDepositState(PosDraftConstants.DEPOSIT_CREDITED);
    }

    /**
     * The booking's bill is being paid: move a held deposit into the customer's wallet so the sale
     * can be paid from it, and mark it applied. Returns what moved to the wallet, or zero. The
     * caller is inside the order's lock and saves the row.
     */
    @Transactional
    public BigDecimal applyDepositToWallet(String businessId, String orderId) {
        PosDraft order = lockRow(businessId, orderId);
        if (!PosDraftConstants.DEPOSIT_HELD.equals(order.getDepositState())) {
            return BigDecimal.ZERO;
        }
        BigDecimal amount = order.getDepositAmount();
        releaseDepositToWallet(businessId, order);
        order.setDepositState(PosDraftConstants.DEPOSIT_APPLIED);
        draftRepository.saveAndFlush(order);
        return amount;
    }

    private static String depositMemo(String what, PosDraft order) {
        return "Deposit " + what + ", booking " + order.getTicketNumber();
    }

    // ------------------------------------------------------------------ reads shared by both shapes

    @Transactional(readOnly = true)
    public Optional<Order> findBySale(String businessId, String saleId) {
        return draftRepository
                .findFirstByBusinessIdAndSaleIdAndDraftKind(businessId, saleId, PosDraftConstants.KIND_ORDER)
                .map(this::toOrder);
    }

    @Transactional(readOnly = true)
    public Optional<Order> find(String businessId, String orderId) {
        return draftRepository
                .findByIdAndBusinessIdAndDraftKind(orderId, businessId, PosDraftConstants.KIND_ORDER)
                .map(this::toOrder);
    }

    /** Order lines by id, for the kitchen to read their progress. Lines of anything but an order are left out. */
    @Transactional(readOnly = true)
    public Map<String, OrderLine> linesById(String businessId, Collection<String> lineIds) {
        Map<String, OrderLine> out = new LinkedHashMap<>();
        if (lineIds == null || lineIds.isEmpty()) {
            return out;
        }
        List<PosDraftLine> rows = lineRepository.findByBusinessIdAndIdIn(businessId, lineIds);
        Set<String> orderIds = new HashSet<>();
        for (PosDraft d : draftRepository.findByBusinessIdAndIdInAndDraftKind(
                businessId, rows.stream().map(PosDraftLine::getDraftId).distinct().toList(),
                PosDraftConstants.KIND_ORDER)) {
            orderIds.add(d.getId());
        }
        for (PosDraftLine row : rows) {
            if (orderIds.contains(row.getDraftId())) {
                out.put(row.getId(), toLine(row));
            }
        }
        return out;
    }

    /**
     * Moves one order line to a kitchen stage. Forward to any later stage (a kitchen may skip
     * "preparing"), or back one step to undo a wrong tap. Asking for the stage it already has
     * changes nothing, so a double tap is harmless. Only the line row is written.
     */
    @Transactional
    public OrderLine setLineStage(String businessId, String lineId, String stage, Instant now) {
        PosDraftLine row = lineRepository.findByIdAndBusinessId(lineId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order line not found"));
        draftRepository.findByIdAndBusinessIdAndDraftKind(row.getDraftId(), businessId, PosDraftConstants.KIND_ORDER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order line not found"));
        int target = STAGE_ORDER.indexOf(stage);
        if (target < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown kitchen stage");
        }
        if (STAGE_VOIDED.equals(row.getLineStage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That line was cancelled");
        }
        int current = STAGE_ORDER.indexOf(row.getLineStage());
        if (current < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That line was not sent to the kitchen");
        }
        if (target == current) {
            return toLine(row);
        }
        if (target < current - 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A line can only go back one step");
        }
        row.setLineStage(stage);
        if (target >= STAGE_ORDER.indexOf(STAGE_READY) && row.getReadyAt() == null) {
            row.setReadyAt(now);
        }
        if (target < STAGE_ORDER.indexOf(STAGE_READY)) {
            row.setReadyAt(null);
        }
        row.setServedAt(STAGE_SERVED.equals(stage) ? now : null);
        lineRepository.save(row);
        return toLine(row);
    }

    /** Marks the lines of a voided sale's order that the kitchen had not finished; returns those lines. */
    @Transactional
    public List<OrderLine> voidUnfinishedLines(String businessId, String orderId) {
        PosDraft order = draftRepository
                .findByIdAndBusinessIdAndDraftKind(orderId, businessId, PosDraftConstants.KIND_ORDER)
                .orElse(null);
        if (order == null) {
            return List.of();
        }
        List<OrderLine> voided = new ArrayList<>();
        for (PosDraftLine row : lineRepository.findByDraftIdOrderByLineIndexAsc(order.getId())) {
            if (STAGE_NEW.equals(row.getLineStage()) || STAGE_PREPARING.equals(row.getLineStage())) {
                row.setLineStage(STAGE_VOIDED);
                lineRepository.save(row);
                voided.add(toLine(row));
            }
        }
        return voided;
    }

    /** Sets the price of a product line that has not been paid for, when the shelf price has moved since it was added. */
    @Transactional
    public void repriceLine(String businessId, String lineId, BigDecimal unitPrice) {
        PosDraftLine row = lineRepository.findByIdAndBusinessId(lineId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order line not found"));
        row.setUnitPrice(unitPrice.setScale(PosDraftConstants.PRICE_SCALE, RoundingMode.HALF_UP));
        row.setLineTotal(lineTotal(row.getQuantity(), row.getUnitPrice()));
        lineRepository.save(row);
    }

    // ------------------------------------------------------------------ internals

    private PosDraft lockRow(String businessId, String orderId) {
        return draftRepository
                .findByIdAndBusinessIdAndDraftKindForUpdate(orderId, businessId, PosDraftConstants.KIND_ORDER)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    private static PosDraft requireOpen(PosDraft order) {
        if (!order.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    PosDraftConstants.STATUS_CANCELLED.equals(order.getStatus())
                            ? "This order was cancelled" : "This order is already settled");
        }
        return order;
    }

    private PosDraftLine requireLine(PosDraft order, String lineId) {
        return lineRepository.findByIdAndDraftId(lineId, order.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order line not found"));
    }

    /** Recomputes the order's total from its live lines and saves it, which also moves its version on. */
    private Order touch(PosDraft order) {
        List<PosDraftLine> rows = lineRepository.findByDraftIdOrderByLineIndexAsc(order.getId());
        BigDecimal total = BigDecimal.ZERO;
        for (PosDraftLine row : rows) {
            if (!STAGE_VOIDED.equals(row.getLineStage())) {
                total = total.add(row.getLineTotal());
            }
        }
        order.setSubTotal(total);
        order.setGrandTotal(total);
        // The total may not have moved (a note changed), so say plainly that the row changed.
        order.setUpdatedAt(Instant.now());
        draftRepository.saveAndFlush(order);
        return toOrder(order, rows);
    }

    private static PosDraftLine newRow(String businessId, String orderId, int index, NewLine line) {
        PosDraftLine row = new PosDraftLine();
        row.setDraftId(orderId);
        row.setBusinessId(businessId);
        row.setLineIndex(index);
        row.setLineKind(line.lineKind());
        row.setItemId(line.itemId());
        row.setMenuItemId(line.menuItemId());
        row.setItemName(line.name().length() > 500 ? line.name().substring(0, 500) : line.name());
        row.setQuantity(line.quantity().setScale(PosDraftConstants.QTY_SCALE, RoundingMode.HALF_UP));
        row.setUnitPrice(line.unitPrice().setScale(PosDraftConstants.PRICE_SCALE, RoundingMode.HALF_UP));
        row.setLineTotal(lineTotal(line.quantity(), line.unitPrice()));
        row.setModifiers(line.modifiers());
        row.setKitchenNote(cleanNote(line.note()));
        row.setStationId(line.stationId());
        row.setSeatNo(line.seatNo() == null || line.seatNo() <= 0 ? null : line.seatNo());
        row.setClientLineId(line.clientLineId());
        return row;
    }

    private static BigDecimal lineTotal(BigDecimal quantity, BigDecimal unitPrice) {
        return quantity.multiply(unitPrice).setScale(PosDraftConstants.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /**
     * A note as it may be stored: one line of ordinary text. A note is typed by anyone, including
     * a customer at a table with nobody signed in, and it ends up on a kitchen ticket and a
     * receipt. Line breaks and control characters are turned into spaces so a note can neither
     * forge extra lines on a ticket nor carry printer commands.
     */
    public static String cleanNote(String raw) {
        if (raw == null) {
            return null;
        }
        String oneLine = raw.replaceAll("[\\p{Cc}\\p{Cf}\\u2028\\u2029]+", " ").replaceAll(" {2,}", " ").trim();
        if (oneLine.isEmpty()) {
            return null;
        }
        return oneLine.length() > 255 ? oneLine.substring(0, 255) : oneLine;
    }

    private static String trim(String raw, int max) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private Order toOrder(PosDraft order) {
        return toOrder(order, lineRepository.findByDraftIdOrderByLineIndexAsc(order.getId()));
    }

    private static Order toOrder(PosDraft order, List<PosDraftLine> rows) {
        return new Order(order.getId(), order.getBranchId(), order.getTicketNumber(), order.getSaleId(),
                order.getOrderChannel(), order.getCreatedAt(), rows.stream().map(OrderTicketService::toLine).toList(),
                order.getStatus(), order.getServiceStage(), order.getTableResourceId(), order.getGuestCount(),
                order.getServedByStaffProfileId(), order.getCustomerId(), order.getBookingName(),
                order.getBookingPhone(), order.getDeliveryAddress(), order.getBillRequestedAt(),
                order.getScheduledStart(), order.getScheduledEnd(), order.getCreatedBy(), order.getVersion(),
                order.getDeliveryFee(), order.getRiderName(), order.getDispatchedAt(), order.getDeliveredAt(),
                order.getBookingSource(),
                order.getDepositAmount(), order.getDepositState(), order.getDepositMethod());
    }

    private static OrderLine toLine(PosDraftLine row) {
        return new OrderLine(row.getId(), row.getDraftId(), row.getLineIndex(), row.getItemName(), row.getQuantity(),
                row.getModifiers(), row.getKitchenNote(), row.getStationId(), row.getLineStage(), row.getSentAt(),
                row.getReadyAt(), row.getServedAt(), row.getLineKind(), row.getItemId(), row.getMenuItemId(),
                row.getUnitPrice(), row.getLineTotal(), row.getSeatNo(), row.getVoidReason(),
                row.getClientLineId() != null && row.getClientLineId().startsWith(CUSTOMER_LINE_PREFIX));
    }
}
