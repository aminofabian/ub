package zelisline.ub.desktop.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/** Buyer accounts mirrored cloud -> till (S3, pull only). Upsert by id. */
public record TradeAccountSyncSnapshot(@Valid List<AccountData> accounts) {

    public record AccountData(
            @NotBlank String id,
            @NotBlank String businessId,
            String phoneKey,
            String buyerPhone,
            String buyerName,
            String businessName,
            String note,
            String status,
            BigDecimal creditLimit,
            Integer termsDays,
            String buyerBusinessId,
            BigDecimal discountPercent,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
