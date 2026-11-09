package zelisline.ub.exports.application;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Persisted export parameters. {@code categoryId} / {@code itemTypeId} are optional
 * filters used by the sales-by-category report; {@code NON_NULL} keeps older rows
 * (which stored only from/to/branchId) deserialisable — missing fields decode to null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportJobParams(
        LocalDate from,
        LocalDate to,
        String branchId,
        String categoryId,
        String itemTypeId
) {
}
