package zelisline.ub.inventory.restock;

import java.math.BigDecimal;

public record RestockOrderLineRow(
        String itemName,
        String itemSku,
        BigDecimal quantity,
        String packLabel,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        String note
) {}
