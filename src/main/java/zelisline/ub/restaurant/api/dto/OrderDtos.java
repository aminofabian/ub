package zelisline.ub.restaurant.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.ChosenModifier;
import zelisline.ub.sales.api.dto.PostSalePaymentRequest;

/** Request and response shapes of the restaurant floor: tables, open orders and their bills. */
public final class OrderDtos {

    private OrderDtos() {
    }

    // ---------------------------------------------------------------- tables

    /** @param qrToken the secret in the table's QR code; null when customers cannot order from it */
    /**
     * @param waiterStaffProfileId the waiter who looks after the table; a new order there is theirs
     *                             unless another is named. Null when no one is assigned
     */
    public record TableResponse(
            String id, String name, Integer capacity, String area, boolean active, int sortOrder, String qrToken,
            String waiterStaffProfileId, String waiterName) {
    }

    public record TableQrRequest(@NotNull Boolean enabled) {
    }

    // ---------------------------------------------------------------- ordering from a table's QR code (public)

    public record PublicOption(String id, String name, BigDecimal priceDelta) {
    }

    public record PublicGroup(String id, String name, int minSelect, int maxSelect, List<PublicOption> options) {
    }

    public record PublicDish(
            String id, String heading, String name, BigDecimal price, List<PublicGroup> groups,
            String description, String imageUrl) {
    }

    /** A dish as the restaurant's web page shows it. Nothing can be ordered from it. */
    public record PublicMenuDish(
            String id, String heading, String name, String description, BigDecimal price, String imageUrl,
            /** False when it is off for now: sold out, or outside the hours it is served. */
            boolean servedNow) {
    }

    public record PublicMenuBranch(String name, String address) {
    }

    /** The menu on the restaurant's public web page: headings in menu order, and what is under them. */
    public record PublicMenu(
            String shopName, String currency, List<String> headings, List<PublicMenuDish> dishes,
            List<PublicMenuBranch> branches) {
    }

    /** What a customer sees after scanning: the shop, the table, and what can be ordered right now. */
    public record PublicTableMenu(String shopName, String tableName, String currency, List<PublicDish> dishes) {
    }

    public record PublicOrderLine(
            @NotBlank String menuItemId,
            @Size(max = 20) List<String> modifierOptionIds,
            @Min(1) @Max(20) int quantity,
            @Size(max = 120) String note) {
    }

    public record PublicOrderRequest(
            /** Made once by the customer's phone for this request; sending it again adds nothing. */
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z0-9-]{8,40}$") String requestId,
            @NotEmpty @Size(max = 20) @Valid List<PublicOrderLine> lines) {
    }

    /** @param added how many lines were put on the order; staff still have to send them to the kitchen */
    public record PublicOrderConfirmation(long orderNumber, String tableName, int added, BigDecimal total) {
    }

    public record TableRequest(
            @Size(max = 36) String branchId,
            @Size(max = 80) String name,
            @Min(0) @Max(500) Integer capacity,
            @Size(max = 60) String area,
            Boolean active,
            /** The waiter who looks after the table. Null leaves it as it is; empty takes them off it. */
            @Size(max = 36) String waiterStaffProfileId) {
    }

    // ---------------------------------------------------------------- the floor

    /** An open order as the floor shows it. */
    public record OrderCard(
            String orderId, long orderNumber, String channel, String tableId, Integer guestCount,
            String servedByStaffProfileId, String servedByName, String customerName, Instant openedAt,
            BigDecimal total, int unsent, int inKitchen, int ready, Instant billRequestedAt,
            boolean booked, Instant scheduledStart, Instant scheduledEnd,
            /** Paid or otherwise closed. Only a delivery still to be delivered is listed in this state. */
            boolean settled,
            /** A delivery: when it left with the rider. */
            Instant dispatchedAt,
            /** What of {@code total} is on no closed bill yet — the money still owed at close. */
            BigDecimal unsettled,
            /** Where the order came from: {@code STAFF}, {@code QR}, {@code ONLINE} (ADR 0031). */
            String source) {
    }

    public record FloorTable(TableResponse table, List<OrderCard> orders) {
    }

    /** @param otherOrders open orders with no table: takeaway, delivery, and bookings not yet given a table */
    public record FloorResponse(String branchId, Instant asOf, List<FloorTable> tables, List<OrderCard> otherOrders) {
    }

    // ---------------------------------------------------------------- an order

    public record OpenOrderRequest(
            @NotBlank @Size(max = 36) String branchId,
            /** {@code DINE_IN}, {@code TAKEAWAY} or {@code DELIVERY}. */
            @NotBlank String channel,
            String tableId,
            @Min(1) @Max(500) Integer guestCount,
            String servedByStaffProfileId,
            String customerId,
            @Size(max = 120) String customerName,
            @Size(max = 32) String customerPhone,
            @Size(max = 500) String deliveryAddress,
            /** Made once by the device for this order; repeating the request returns the same order. */
            @NotBlank @Size(max = 56) String clientOrderId,
            /**
             * For a table: set both to book it for later instead of seating it now. For a takeaway
             * or delivery: when it is wanted; the order is open at once.
             */
            Instant scheduledStart,
            Instant scheduledEnd,
            /** A delivery order's own fee; left out, the business's usual fee applies. */
            @DecimalMin("0") BigDecimal deliveryFee) {
    }

    public record MergeRequest(@NotBlank String intoOrderId) {
    }

    /** Null leaves a field as it is. */
    public record DeliveryRequest(
            @DecimalMin("0") BigDecimal deliveryFee, @Size(max = 120) String riderName, Boolean dispatched,
            Boolean delivered) {
    }

    public record ServerResponse(String staffProfileId, String name) {
    }

    public record NewOrderLine(
            /** Made once by the device for this line; a repeated request does not add it twice. */
            @NotBlank @Size(max = 64) String clientLineId,
            @NotBlank String menuItemId,
            @Size(max = 40) List<String> modifierOptionIds,
            @NotNull @DecimalMin("0.0001") BigDecimal quantity,
            @Size(max = 255) String note,
            @Min(0) @Max(99) Integer seatNo) {
    }

    public record AddLinesRequest(@NotEmpty @Size(max = 60) @Valid List<NewOrderLine> lines) {
    }

    /** Null leaves a field as it is. A quantity of zero removes the line. */
    public record ChangeLineRequest(
            @DecimalMin("0") BigDecimal quantity, @Size(max = 255) String note, @Min(0) @Max(99) Integer seatNo) {
    }

    public record VoidLineRequest(@Size(max = 255) String reason) {
    }

    public record MoveRequest(String tableId) {
    }

    /** Null leaves a field as it is; a blank clears it. */
    public record OrderDetailsRequest(
            @Min(1) @Max(500) Integer guestCount,
            String servedByStaffProfileId,
            String customerId,
            @Size(max = 120) String customerName,
            @Size(max = 32) String customerPhone,
            @Size(max = 500) String deliveryAddress) {
    }

    public record BillRequestedRequest(@NotNull Boolean requested) {
    }

    public record CancelOrderRequest(@Size(max = 255) String reason,
            /** When the booking has a held deposit: hand it back ({@code cash}/{@code mpesa_manual} in {@code depositBack}), else it becomes the customer's credit. */
            Boolean refundsDeposit, String depositBack) {

        public boolean refundsDepositOrFalse() {
            return Boolean.TRUE.equals(refundsDeposit);
        }
    }

    /**
     * @param stage {@code DRAFT} (not sent), {@code NEW}, {@code PREPARING}, {@code READY}, {@code SERVED} or {@code VOIDED}
     * @param billedQuantity how much of the line is on a bill that is open or settled
     * @param settledQuantity how much of it is on a bill that is paid or otherwise closed
     */
    public record OrderLineResponse(
            String id, String kind, String menuItemId, String itemId, String name, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal lineTotal, List<ChosenModifier> modifiers, String note,
            Integer seatNo, String stage, Instant sentAt, BigDecimal billedQuantity, BigDecimal settledQuantity,
            String voidReason,
            /** The customer added this line from the table's QR code; staff have not confirmed it until it is sent. */
            boolean fromCustomer) {
    }

    public record BillLineResponse(String orderLineId, String name, BigDecimal quantity, BigDecimal amount) {
    }

    /** @param status {@code OPEN}, {@code PAID}, {@code COMP}, {@code UNPAID} or {@code VOIDED} */
    /**
     * @param total what the bill's lines come to
     * @param toPay the lines plus the service charge, packaging charge and delivery fee
     */
    public record BillResponse(
            String id, int billNo, String status, BigDecimal total, String saleId, Long receiptNo, String reason,
            List<BillLineResponse> lines, BigDecimal serviceCharge, BigDecimal packagingCharge,
            BigDecimal deliveryFee, BigDecimal toPay) {
    }

    /**
     * @param status {@code pending} while open, {@code completed} once every line is settled, {@code cancelled}
     * @param total everything not cancelled; {@code settled} is what closed bills cover, {@code unbilled} what is on no bill
     */
    public record OrderResponse(
            String id, long orderNumber, String branchId, String channel, String status, boolean booked,
            String tableId, String tableName, Integer guestCount, String servedByStaffProfileId,
            String servedByName, String customerId, String customerName, String customerPhone,
            String deliveryAddress, Instant openedAt, Instant billRequestedAt, Instant scheduledStart,
            Instant scheduledEnd, long version, BigDecimal total, BigDecimal settled, BigDecimal unbilled,
            List<OrderLineResponse> lines, List<BillResponse> bills,
            /** A delivery order's fee (its own, else the usual one), rider and progress. */
            BigDecimal deliveryFee, String riderName, Instant dispatchedAt, Instant deliveredAt,
            /** {@code STAFF}, or {@code QR} when a customer started it from a table's QR code. */
            String source,
            /** A deposit held against a booking: its amount, and its state ({@code HELD}, {@code APPLIED}, {@code CREDITED}, {@code FORFEITED}, {@code REFUNDED}), or null when none. */
            BigDecimal depositAmount, String depositState,
            /** A lodging guest's ID type and number, captured at check-in (ADR 0031). Sensitive PII. */
            String guestIdType, String guestIdNumber) {
    }

    /** Record (or clear) a lodging guest's ID on their stay (ADR 0031). */
    public record GuestIdRequest(
            @Size(max = 32) String idType,
            @Size(max = 64) String idNumber) {
    }

    public record DepositRequest(
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            /** {@code cash} or {@code mpesa_manual}. */
            @NotBlank String method) {
    }

    public record NoShowRequest(Boolean keepDepositAsCredit) {

        public boolean keepAsCredit() {
            return Boolean.TRUE.equals(keepDepositAsCredit);
        }
    }

    // ---------------------------------------------------------------- bills

    public record BillLineRequest(@NotBlank String orderLineId, @NotNull @DecimalMin("0.0001") BigDecimal quantity) {
    }

    /**
     * Either {@code lines} (this bill covers exactly these), or {@code seatNo} (everything for that
     * seat not yet on a bill), or neither (everything not yet on a bill).
     */
    public record CreateBillRequest(@Size(max = 300) @Valid List<BillLineRequest> lines, @Min(1) @Max(99) Integer seatNo) {
    }

    public record PayBillRequest(
            @NotEmpty @Valid List<PostSalePaymentRequest> payments,
            /** For whoever served the order. Not part of the bill total and never revenue. */
            @DecimalMin("0") BigDecimal tip,
            @DecimalMin("0") BigDecimal cashReceived,
            Instant clientSoldAt) {
    }

    /** @param outcome {@code COMP} (on the house) or {@code UNPAID} (left without paying) */
    public record CloseBillRequest(@NotBlank String outcome, @NotBlank @Size(max = 255) String reason) {
    }
}
