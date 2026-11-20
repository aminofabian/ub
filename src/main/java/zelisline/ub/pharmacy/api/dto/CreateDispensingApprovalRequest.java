package zelisline.ub.pharmacy.api.dto;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * A pharmacist approves, by PIN, the medicines and quantities shown on the till. The sale may
 * hold less of a medicine than was approved, never more.
 */
public record CreateDispensingApprovalRequest(
        @NotBlank @Size(max = 36) String approverUserId,
        @NotBlank @Size(max = 32) String pin,
        @NotEmpty @Size(max = 40) List<@Valid @NotNull Line> lines
) {
    public record Line(
            @NotBlank @Size(max = 36) String itemId,
            @NotNull @Positive BigDecimal quantity
    ) {
    }

    /** Quantity approved per item; the same item on two lines is added up. */
    public Map<String, BigDecimal> quantitiesByItemId() {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (lines != null) {
            for (Line l : lines) {
                if (l != null && l.itemId() != null && !l.itemId().isBlank() && l.quantity() != null) {
                    out.merge(l.itemId().strip(), l.quantity(), BigDecimal::add);
                }
            }
        }
        return out;
    }
}
