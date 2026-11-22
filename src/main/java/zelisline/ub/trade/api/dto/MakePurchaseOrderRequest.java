package zelisline.ub.trade.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Turns a confirmed order from a supplier into this shop's own purchase order. {@code matches}
 * names this shop's product for any line that did not match by barcode, or overrides one that did.
 */
public record MakePurchaseOrderRequest(
        @Size(max = 36) String branchId,
        @Valid @Size(max = 200) List<Match> matches
) {
    public record Match(@Min(1) int lineNo, @NotBlank @Size(max = 36) String itemId) {
    }
}
