package zelisline.ub.inventory.restock;

import java.math.BigDecimal;
import java.util.List;

public record RestockOrderSnapshot(
        String businessName,
        String branchName,
        String orderNumber,
        String orderDateDisplay,
        String supplierName,
        String supplierPhone,
        String supplierEmail,
        String adminNotes,
        List<RestockOrderLineRow> lines,
        BigDecimal supplierSubtotal
) {}
