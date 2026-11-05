package zelisline.ub.globalcatalog.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record PreviewAdoptRequest(
        @NotEmpty @Valid List<AdoptLineRequest> lines,
        /** When true, preview assumes missing slug-hint categories will be created. Default false. */
        Boolean createMissingCategories,
        /** When true, selected parents also bring their published variants (expand down). */
        Boolean includeVariants
) {
}
