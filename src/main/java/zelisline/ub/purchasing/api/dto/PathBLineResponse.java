package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PathBLineResponse(
        String id,
        int sortOrder,
        String descriptionText,
        BigDecimal amountMoney,
        String suggestedItemId,
        String lineStatus
) {
}
