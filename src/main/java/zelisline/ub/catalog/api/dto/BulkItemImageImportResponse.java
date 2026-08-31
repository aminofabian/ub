package zelisline.ub.catalog.api.dto;

import java.util.List;

/** Result of a bulk item-image import (CSV of {@code sku,image_url}). */
public record BulkItemImageImportResponse(
        int rowsParsed,
        int updated,
        List<RowIssue> notFound,
        List<RowIssue> invalid
) {
    public record RowIssue(int line, String sku, String message) {
    }
}
