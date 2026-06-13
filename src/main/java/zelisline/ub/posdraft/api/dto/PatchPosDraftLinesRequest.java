package zelisline.ub.posdraft.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record PatchPosDraftLinesRequest(
        @NotEmpty @Valid List<PosDraftLineInput> lines,
        Long expectedVersion
) {
}
