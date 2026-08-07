package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

public record PathBSupplyExpenseDto(
        String id,
        String category,
        BigDecimal amount,
        String description
) {
}
