package zelisline.ub.restaurant.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.posdraft.application.OrderTicketService;
import zelisline.ub.restaurant.api.dto.OrderDtos.BillLineRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.CreateBillRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.OrderResponse;
import zelisline.ub.restaurant.api.dto.OrderDtos.PayBillRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.ChosenModifier;
import zelisline.ub.restaurant.domain.MenuItem;
import zelisline.ub.restaurant.domain.OrderBill;
import zelisline.ub.restaurant.domain.OrderBillLine;
import zelisline.ub.restaurant.repository.MenuItemRepository;
import zelisline.ub.restaurant.repository.OrderBillLineRepository;
import zelisline.ub.restaurant.repository.OrderBillRepository;
import zelisline.ub.sales.api.dto.PostSaleLineRequest;
import zelisline.ub.sales.api.dto.PostSaleRequest;
import zelisline.ub.sales.api.dto.PostSaleTipRequest;
import zelisline.ub.sales.application.OrderSettlement;
import zelisline.ub.sales.application.SaleCreationOutcome;
import zelisline.ub.sales.application.SaleService;
import zelisline.ub.sales.domain.SaleLineKinds;

/**
 * Settling an order (ADR 0020). A bill is some or all of an order's lines; paying it posts one
 * ordinary sale through {@link SaleService}, so tenders, the drawer, the ledger, tax and stock
 * behave exactly as they do at any till. An order paid by three people is three bills and three
 * sales. The order is finished when every line that was not cancelled is on a closed bill.
 *
 * <p>Every change here takes the order's row lock first, so two devices billing one table take
 * turns and a line can never be put on two bills.
 */
@Service
@RequiredArgsConstructor
public class BillService {

    private final OrderTicketService orderTicketService;
    private final DineInService dineInService;
    private final OrderBillRepository billRepository;
    private final OrderBillLineRepository billLineRepository;
    private final MenuItemRepository menuItemRepository;
    private final SaleService saleService;
    private final RestaurantSettingsService settingsService;
    private final zelisline.ub.payroll.application.StaffProfileService staffProfileService;
    private final RestaurantGate gate;

    /**
     * Opens a bill for the lines named, for one seat, or for everything not yet on a bill.
     * Refused while anything on the order has not been sent: a bill is for food that was ordered.
     */
    @Transactional
    public OrderResponse create(String businessId, String orderId, String userId, CreateBillRequest request) {
        gate.requireEnabled(businessId);
        OrderTicketService.Order order = requireOpen(orderTicketService.lock(businessId, orderId));
        if (order.isBooked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat the booking first");
        }
        if (order.lines().stream().anyMatch(OrderTicketService.OrderLine::isDraft)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Some items have not been sent to the kitchen; send or remove them before the bill");
        }
        Map<String, BigDecimal> billed = dineInService.billedQuantity(businessId, orderId);
        Map<String, BigDecimal> wanted = new LinkedHashMap<>();
        if (request != null && request.lines() != null && !request.lines().isEmpty()) {
            Map<String, OrderTicketService.OrderLine> byId = new HashMap<>();
            for (OrderTicketService.OrderLine line : order.lines()) {
                byId.put(line.id(), line);
            }
            for (BillLineRequest row : request.lines()) {
                OrderTicketService.OrderLine line = byId.get(row.orderLineId());
                if (line == null || line.isVoided()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That line is not on this order");
                }
                if ("DISH".equals(line.lineKind()) && row.quantity().stripTrailingZeros().scale() > 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            line.name() + " cannot be shared between bills in parts; put it on one bill");
                }
                wanted.merge(line.id(), row.quantity(), BigDecimal::add);
            }
            for (Map.Entry<String, BigDecimal> entry : wanted.entrySet()) {
                OrderTicketService.OrderLine line = byId.get(entry.getKey());
                BigDecimal free = line.quantity().subtract(billed.getOrDefault(line.id(), BigDecimal.ZERO));
                if (entry.getValue().compareTo(free) > 0) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Only " + free.stripTrailingZeros().toPlainString() + " of " + line.name()
                                    + " is not on a bill yet");
                }
            }
        } else {
            Integer seat = request == null ? null : request.seatNo();
            for (OrderTicketService.OrderLine line : order.lines()) {
                if (line.isVoided() || (seat != null && !seat.equals(line.seatNo()))) {
                    continue;
                }
                BigDecimal free = line.quantity().subtract(billed.getOrDefault(line.id(), BigDecimal.ZERO));
                if (free.signum() > 0) {
                    wanted.put(line.id(), free);
                }
            }
        }
        if (wanted.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "There is nothing left to put on a bill");
        }
        List<OrderBill> existing = billRepository.findByBusinessIdAndOrderIdOrderByBillNoAsc(businessId, orderId);
        OrderBill bill = new OrderBill();
        bill.setBusinessId(businessId);
        bill.setBranchId(order.branchId());
        bill.setOrderId(orderId);
        bill.setBillNo(existing.stream().mapToInt(OrderBill::getBillNo).max().orElse(0) + 1);
        bill.setCreatedBy(userId);
        billRepository.save(bill);
        for (Map.Entry<String, BigDecimal> entry : wanted.entrySet()) {
            OrderBillLine row = new OrderBillLine();
            row.setBusinessId(businessId);
            row.setBillId(bill.getId());
            row.setOrderId(orderId);
            row.setOrderLineId(entry.getKey());
            row.setQuantity(entry.getValue().setScale(4, RoundingMode.HALF_UP));
            billLineRepository.save(row);
        }
        return dineInService.toResponse(businessId, dineInService.require(businessId, orderId));
    }

    /** Tears up a bill nobody has paid; its lines can go on another. */
    @Transactional
    public OrderResponse discard(String businessId, String orderId, String billId) {
        gate.requireEnabled(businessId);
        orderTicketService.lock(businessId, orderId);
        OrderBill bill = requireBill(businessId, orderId, billId);
        if (!OrderBill.OPEN.equals(bill.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That bill is already closed");
        }
        billLineRepository.deleteByBusinessIdAndBillId(businessId, billId);
        billRepository.delete(bill);
        return dineInService.toResponse(businessId, dineInService.require(businessId, orderId));
    }

    /**
     * Takes payment for a bill. The sale's idempotency key is the bill itself, so paying the same
     * bill twice — a double tap, a retry after a dropped connection — rings one sale.
     */
    @Transactional(noRollbackFor = zelisline.ub.posdraft.application.TicketRepricedException.class)
    public OrderResponse pay(
            String businessId, String orderId, String billId, PayBillRequest request, String userId, String roleId,
            String tillDeviceKey) {
        gate.requireEnabled(businessId);
        OrderTicketService.Order order = orderTicketService.lock(businessId, orderId);
        OrderBill bill = requireBill(businessId, orderId, billId);
        if (OrderBill.PAID.equals(bill.getStatus())) {
            return dineInService.toResponse(businessId, order);
        }
        if (!OrderBill.OPEN.equals(bill.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That bill is already closed");
        }
        Map<String, OrderTicketService.OrderLine> byId = new HashMap<>();
        for (OrderTicketService.OrderLine line : order.lines()) {
            byId.put(line.id(), line);
        }
        List<PostSaleLineRequest> saleLines = new ArrayList<>();
        Map<Integer, OrderSettlement.FrozenDish> dishes = new HashMap<>();
        List<String> repriced = new ArrayList<>();
        for (OrderBillLine row : billLineRepository.findByBusinessIdAndBillId(businessId, billId)) {
            OrderTicketService.OrderLine line = byId.get(row.getOrderLineId());
            if (line == null || line.isVoided()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Something on this bill was cancelled; discard the bill and make it again");
            }
            if ("DISH".equals(line.lineKind())) {
                List<ChosenModifier> chosen = ModifierSnapshots.decode(line.modifiers());
                String label = line.name();
                if (!chosen.isEmpty()) {
                    label = label + " (" + String.join(", ", chosen.stream().map(ChosenModifier::option).toList()) + ")";
                }
                if (label.length() > 255) {
                    label = label.substring(0, 252) + "...";
                }
                // The dish's tax rate as it is now; a dish since removed from the menu carries none.
                String taxRateId = menuItemRepository.findByIdAndBusinessId(line.menuItemId(), businessId)
                        .map(MenuItem::getTaxRateId).orElse(null);
                dishes.put(saleLines.size(), new OrderSettlement.FrozenDish(line.menuItemId(), line.name(), label,
                        line.modifiers(), taxRateId, line.unitPrice().setScale(2, RoundingMode.HALF_UP), line.note()));
                saleLines.add(new PostSaleLineRequest(null, row.getQuantity(), line.unitPrice(), SaleLineKinds.DISH,
                        null, null, null, null, null, null, null, null, null, null, line.menuItemId(), null, null));
            } else {
                // A product sells at today's shelf price, as it would at the till. If that moved since
                // it was ordered, the bill has changed and the customer must be told before paying.
                BigDecimal current = dineInService.shelfPrice(businessId, order.branchId(), line.itemId(), line.name());
                if (current.compareTo(line.unitPrice()) != 0) {
                    orderTicketService.repriceLine(businessId, line.id(), current);
                    repriced.add(line.name());
                }
                saleLines.add(PostSaleLineRequest.catalogItem(line.itemId(), row.getQuantity(), current));
            }
        }
        if (!repriced.isEmpty()) {
            // Committed, not rolled back (see noRollbackFor): the order keeps its new prices.
            throw new zelisline.ub.posdraft.application.TicketRepricedException("The price of " + String.join(", ", repriced)
                    + " has changed since it was ordered. Check the bill and take payment again.");
        }
        if (saleLines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This bill is empty; discard it");
        }
        // What the bill adds to its lines, worked out exactly as it was shown.
        zelisline.ub.restaurant.domain.RestaurantSettings settings = settingsService.get(businessId);
        BigDecimal linesTotal = BigDecimal.ZERO;
        for (PostSaleLineRequest line : saleLines) {
            linesTotal = linesTotal.add(line.quantity().multiply(line.unitPrice()).setScale(2, RoundingMode.HALF_UP));
        }
        BillCharges.Charges charges = BillCharges.forBill(order, bill,
                billRepository.findByBusinessIdAndOrderIdOrderByBillNoAsc(businessId, orderId), linesTotal, settings);
        // A service charge is either the restaurant's income, sold as a line of the bill, or money
        // collected for the people who serve, which is shared out exactly as a tip is. The customer
        // pays the same either way; only where it lands differs (ADR 0020, amendment 4).
        boolean chargeToStaff = settings.serviceChargeGoesToStaff() && charges.serviceCharge().signum() > 0;
        if (!chargeToStaff) {
            addCharge(saleLines, "Service charge", charges.serviceCharge());
        }
        addCharge(saleLines, "Packaging", charges.packagingCharge());
        addCharge(saleLines, "Delivery", charges.deliveryFee());

        BigDecimal forStaff = request.tip() == null ? BigDecimal.ZERO : request.tip();
        if (chargeToStaff) {
            forStaff = forStaff.add(charges.serviceCharge());
        }
        Map<String, BigDecimal> tipShares = tipShares(businessId, order, settings, forStaff, chargeToStaff);
        List<PostSaleTipRequest> tips = tipShares.isEmpty() ? null : tipShares.entrySet().stream()
                .map(e -> new PostSaleTipRequest(e.getKey(), e.getValue())).toList();
        PostSaleRequest saleRequest = new PostSaleRequest(order.branchId(), saleLines, request.payments(),
                request.clientSoldAt(), order.customerId(), request.cashReceived(), null, tips);
        OrderSettlement.Context context = new OrderSettlement.Context(orderId, dishes, tipShares.keySet());
        SaleCreationOutcome outcome = OrderSettlement.settling(context,
                () -> saleService.createSale(businessId, "bill-" + billId, saleRequest, userId, roleId, tillDeviceKey));
        Instant now = Instant.now();
        bill.setStatus(OrderBill.PAID);
        bill.setServiceCharge(charges.serviceCharge());
        bill.setPackagingCharge(charges.packagingCharge());
        bill.setDeliveryFee(charges.deliveryFee());
        bill.setSaleId(outcome.response().id());
        bill.setClosedBy(userId);
        bill.setClosedAt(now);
        billRepository.saveAndFlush(bill);
        completeIfSettled(businessId, orderId, outcome.response().id(), outcome.response().shiftId(), now);
        return dineInService.toResponse(businessId, dineInService.require(businessId, orderId));
    }

    private static void addCharge(List<PostSaleLineRequest> saleLines, String label, BigDecimal amount) {
        if (amount != null && amount.signum() > 0) {
            saleLines.add(new PostSaleLineRequest(null, BigDecimal.ONE, amount, SaleLineKinds.CHARGE, label, null, null,
                    null, null, null, null, null, null, null, null, null, null));
        }
    }

    /**
     * Who gets the tip. Ordinarily whoever served the order. When the restaurant pools tips, it is
     * shared equally among everyone marked as serving customers; any cents left over go to whoever
     * served, so the parts always add up to the tip.
     */
    private Map<String, BigDecimal> tipShares(
            String businessId, OrderTicketService.Order order, zelisline.ub.restaurant.domain.RestaurantSettings settings,
            BigDecimal tip, boolean includesServiceCharge) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (tip == null || tip.signum() <= 0) {
            return out;
        }
        BigDecimal whole = tip.setScale(2, RoundingMode.HALF_UP);
        List<String> pool = settings.isTipsPooled()
                ? staffProfileService.findBookableProfileIds(businessId).stream().sorted().toList() : List.of();
        if (pool.isEmpty()) {
            if (order.servedByStaffProfileId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, includesServiceCharge
                        ? "Say who served this order: its service charge goes to them"
                        : "Say who served this order before taking a tip");
            }
            out.put(order.servedByStaffProfileId(), whole);
            return out;
        }
        BigDecimal each = whole.divide(BigDecimal.valueOf(pool.size()), 2, RoundingMode.DOWN);
        BigDecimal left = whole.subtract(each.multiply(BigDecimal.valueOf(pool.size())));
        String keeper = order.servedByStaffProfileId() != null && pool.contains(order.servedByStaffProfileId())
                ? order.servedByStaffProfileId() : pool.get(0);
        for (String staff : pool) {
            BigDecimal share = staff.equals(keeper) ? each.add(left) : each;
            if (share.signum() > 0) {
                out.put(staff, share);
            }
        }
        return out;
    }

    /**
     * Closes a bill with no sale: on the house, or the customer left without paying. Needs its own
     * permission and a reason, so a meal that was cooked is never simply made to disappear.
     */
    @Transactional
    public OrderResponse closeWithoutSale(
            String businessId, String orderId, String billId, String outcome, String reason, String userId) {
        gate.requireEnabled(businessId);
        orderTicketService.lock(businessId, orderId);
        OrderBill bill = requireBill(businessId, orderId, billId);
        String status = outcome == null ? "" : outcome.trim().toUpperCase(java.util.Locale.ROOT);
        if (!OrderBill.COMP.equals(status) && !OrderBill.UNPAID.equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A bill is closed as on the house or as unpaid");
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say why");
        }
        if (bill.getStatus().equals(status)) {
            return dineInService.toResponse(businessId, dineInService.require(businessId, orderId));
        }
        if (!OrderBill.OPEN.equals(bill.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That bill is already closed");
        }
        Instant now = Instant.now();
        bill.setStatus(status);
        bill.setReason(reason.trim().length() > 255 ? reason.trim().substring(0, 255) : reason.trim());
        bill.setClosedBy(userId);
        bill.setClosedAt(now);
        billRepository.saveAndFlush(bill);
        completeIfSettled(businessId, orderId, null, null, now);
        return dineInService.toResponse(businessId, dineInService.require(businessId, orderId));
    }

    /** The order is finished once nothing is unsent, no bill is open and every live line is fully on closed bills. */
    private void completeIfSettled(String businessId, String orderId, String lastSaleId, String shiftId, Instant now) {
        OrderTicketService.Order order = dineInService.require(businessId, orderId);
        if (!order.isOpen()) {
            return;
        }
        if (billRepository.findByBusinessIdAndOrderIdOrderByBillNoAsc(businessId, orderId).stream()
                .anyMatch(b -> OrderBill.OPEN.equals(b.getStatus()))) {
            return;
        }
        Map<String, BigDecimal> settled = dineInService.settledQuantity(businessId, orderId);
        boolean anyLive = false;
        for (OrderTicketService.OrderLine line : order.lines()) {
            if (line.isVoided()) {
                continue;
            }
            anyLive = true;
            if (line.isDraft() || settled.getOrDefault(line.id(), BigDecimal.ZERO).compareTo(line.quantity()) < 0) {
                return;
            }
        }
        if (anyLive) {
            orderTicketService.complete(businessId, orderId, lastSaleId, shiftId, now);
        }
    }

    private OrderBill requireBill(String businessId, String orderId, String billId) {
        return billRepository.findByIdAndBusinessId(billId, businessId)
                .filter(b -> b.getOrderId().equals(orderId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bill not found"));
    }

    private static OrderTicketService.Order requireOpen(OrderTicketService.Order order) {
        if (!order.isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is already settled or cancelled");
        }
        return order;
    }
}
