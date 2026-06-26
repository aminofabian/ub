package zelisline.ub.globalcatalog.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record PreviewAdoptRequest(
        @NotEmpty @Valid List<AdoptLineRequest> lines
) {
}
