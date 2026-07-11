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
        String soldAtDisplay,
        String saleStatus,
        List<ReceiptLineRow> lines,
        List<ReceiptPaymentRow> payments,
        String grandTotalDisplay,
        String footerNote
) {
}
