package zelisline.ub.credits.api.dto;

import java.time.Instant;
import java.util.List;

public record CustomerResponse(
        String id,
        Long customerNo,
        String name,
        String firstName,
        String lastName,
        String origin,
        String email,
        String notes,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<CustomerPhoneResponse> phones,
        CreditAccountSummaryResponse credit
) {
}
