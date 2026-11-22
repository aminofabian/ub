package zelisline.ub.wholesale.api.dto;

/** The account a counter invoice was priced for. {@code found} is false when the clerk picked none. */
public record CounterInvoiceAccountResponse(
        boolean found,
        String accountId,
        String buyerName,
        String businessName,
        /** The buyer's phone number, as written on the invoice. */
        String buyerPhone
) {

    public static CounterInvoiceAccountResponse none() {
        return new CounterInvoiceAccountResponse(false, null, null, null, null);
    }
}
