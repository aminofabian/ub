package zelisline.ub.tenancy.api.dto;

import java.util.List;

/**
 * The business's shop formats for the formats editor: the current set, and for every known format
 * whether it is on and whether it can be removed right now (with a reason when it cannot).
 */
public record BusinessFormatsResponse(
        List<String> storeTypes,
        List<FormatOption> formats) {

    public record FormatOption(String code, boolean selected, boolean removable, String lockedReason) {}
}
