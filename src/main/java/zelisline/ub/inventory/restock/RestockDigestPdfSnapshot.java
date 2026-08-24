package zelisline.ub.inventory.restock;

import java.math.BigDecimal;
import java.util.List;

public record RestockDigestPdfSnapshot(
        String businessName,
        String branchName,
        String runDateDisplay,
        String groupTitle,
        String groupHint,
        String currency,
        List<RestockDigestPdfLine> lines,
        BigDecimal subtotal
) {}
