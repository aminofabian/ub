package zelisline.ub.globalcatalog.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record RefreshCatalogRequest(
        @NotBlank @Size(max = 36) String branchId,
        @NotEmpty List<@NotBlank String> globalProductIds,
        Boolean refreshSellingPrice,
        Boolean refreshBuyingPrice,
        Boolean refreshImage,
        /** When true with refreshImage, re-host even if a cover already exists. */
        Boolean forceImage,
        /**
         * When true, skip selling updates if the shop already has an open sell price that
         * differs from the template (treat as customized). Default false — caller must
         * confirm overwrite; otherwise stale adopted prices can never refresh.
         */
        Boolean skipCustomizedSellingPrice
) {
}
