package zelisline.ub.sales.receipt;

public record ReceiptLineRow(String description, String quantity, String unitType, String unitPrice, String lineTotal) {
}
