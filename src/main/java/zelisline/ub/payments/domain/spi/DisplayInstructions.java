package zelisline.ub.payments.domain.spi;

/**
 * Display-only instructions for manual payment methods
 * (Till number, Paybill details, bank account info).
 */
public record DisplayInstructions(
        String configId,
        String type,
        String label,
        String instructions,
        String tillNumber,
        String businessNumber,
        String accountNumber,
        String bankName,
        String branchName,
        String accountName,
        String swiftCode
) {
}
