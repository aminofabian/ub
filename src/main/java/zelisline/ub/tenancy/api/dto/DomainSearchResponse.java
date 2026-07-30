package zelisline.ub.tenancy.api.dto;

import java.util.List;

public record DomainSearchResponse(
        String query,
        String currency,
        List<DomainQuoteDto> results,
        List<String> suggestions,
        String warning
) {
    public record DomainQuoteDto(
            String domain,
            boolean available,
            String status,
            Long priceCents,
            String currency,
            Integer periodYears,
            boolean premium,
            boolean requiresAdditionalInfo
    ) {
    }
}
