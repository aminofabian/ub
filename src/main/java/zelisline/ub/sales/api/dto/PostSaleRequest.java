package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;

public record PostSaleRequest(
        @NotBlank String branchId,
        @NotEmpty @Valid List<PostSaleLineRequest> lines,
        @NotEmpty @Valid List<PostSalePaymentRequest> payments,
        /** When set (e.g. offline POS), may be replaced by server time if skew exceeds policy. */
        Instant clientSoldAt,
        /** Required when any payment uses {@code customer_credit} (tab / AR). */
        String customerId,
        /**
         * Notes the customer handed over. Used for receipt "Received" / "Change".
         * On a cash sale this is at least the bill. On a split it only has to
         * cover the cash share — M-Pesa or wallet may cover the rest.
         */
        @PositiveOrZero BigDecimal cashReceived,
        /**
         * Admin override: let this one sale oversell items whose stock is exhausted. The caller
         * must hold {@code inventory.write}; the flag is ignored (and the sale rejected as usual)
         * otherwise. {@code null}/false = normal stock rules.
         */
        Boolean allowOutOfStock,
        /**
         * Tips for staff who performed a service on this sale. Not part of the sale total and
         * never revenue; the payments must cover the total plus the tips.
         */
        @Valid List<PostSaleTipRequest> tips,
        /**
         * Required when a line is a prescription-only or controlled medicine: who prescribed it,
         * and the pharmacist's approval when the cashier may not dispense (ADR 0033).
         */
        @Valid PostSaleDispensingRequest dispensing,
        /**
         * The name a counter order is called by ("Kevin"). Not a customer record and never a phone
         * number; it rides on the order and the kitchen docket so a takeaway can be called by name.
         */
        @jakarta.validation.constraints.Size(max = 120) String customerName
) {
    /** Shape used before a counter order could carry a name; older queued sales still build it. */
    public PostSaleRequest(
            String branchId,
            List<PostSaleLineRequest> lines,
            List<PostSalePaymentRequest> payments,
            Instant clientSoldAt,
            String customerId,
            BigDecimal cashReceived,
            Boolean allowOutOfStock,
            List<PostSaleTipRequest> tips,
            PostSaleDispensingRequest dispensing
    ) {
        this(branchId, lines, payments, clientSoldAt, customerId, cashReceived, allowOutOfStock, tips, dispensing, null);
    }

    /** Shape used before dispensing existed; older queued sales and internal callers still build it. */
    public PostSaleRequest(
            String branchId,
            List<PostSaleLineRequest> lines,
            List<PostSalePaymentRequest> payments,
            Instant clientSoldAt,
            String customerId,
            BigDecimal cashReceived,
            Boolean allowOutOfStock,
            List<PostSaleTipRequest> tips
    ) {
        this(branchId, lines, payments, clientSoldAt, customerId, cashReceived, allowOutOfStock, tips, null, null);
    }

    /** Shape used before tips existed; older queued sales and internal callers still build it. */
    public PostSaleRequest(
            String branchId,
            List<PostSaleLineRequest> lines,
            List<PostSalePaymentRequest> payments,
            Instant clientSoldAt,
            String customerId,
            BigDecimal cashReceived,
            Boolean allowOutOfStock
    ) {
        this(branchId, lines, payments, clientSoldAt, customerId, cashReceived, allowOutOfStock, null, null, null);
    }
}
