package zelisline.ub.sales.receipt;

import java.util.List;

public record ReceiptSnapshot(
        String businessName,
        String branchName,
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
