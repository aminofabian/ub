package zelisline.ub.inventory.restock;

import java.math.BigDecimal;

public record RestockDigestPdfLine(
        String itemName,
        String itemSku,
        String departmentName,
        String supplierName,
        BigDecimal onHand,
        BigDecimal par,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal lineTotal,
        String evidence
) {}
