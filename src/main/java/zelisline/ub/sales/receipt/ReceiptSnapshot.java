package zelisline.ub.sales.receipt;

import java.util.List;

public record ReceiptSnapshot(
        String businessName,
        String logoUrl,
        String branchName,
        String branchAddress,
        String branchPhone,
        String branchEmail,
        String branchWebsite,
        String tillNumber,
        String branchReceiptMessage,
        String servedByName,
        String currency,
        String saleId,
        /** Short sequential receipt number per business; null for pre-migration sales. */
        Long receiptNo,
        String soldAtDisplay,
        String saleStatus,
        List<ReceiptLineRow> lines,
        List<ReceiptPaymentRow> payments,
        String grandTotalDisplay,
        /** Cash handed over; null when not applicable. */
        String cashReceivedDisplay,
        /** Change = cashReceived - grandTotal; null when cashReceived is null. */
        String changeGivenDisplay,
        String footerNote
) {

    /** "Receipt #12" when numbered, else short sale id for pre-migration sales. */
    public String receiptLabel() {
        if (receiptNo != null) {
            return "Receipt #" + receiptNo;
        }
        String id = saleId == null ? "" : saleId;
        return "Sale " + (id.length() > 8 ? id.substring(0, 8) : id);
    }
}
