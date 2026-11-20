package zelisline.ub.pharmacy.api.dto;

import java.util.List;

/**
 * Where the medicines in a cart stand. {@code status} is one of {@code ok}, {@code needs_record},
 * {@code unclassified}, {@code controlled_blocked}. Items that are not medicines are left out.
 */
public record DispensingCheckResponse(
        List<Line> lines,
        boolean needsRecord,
        boolean blocked,
        boolean userMayDispense
) {

    public record Line(String itemId, String name, String dispensingClass, String status) {
    }
}
