package zelisline.ub.posdraft.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreatePosDraftRequest(
        @NotBlank String branchId,
        @NotBlank String clientDraftId,
        @NotEmpty @Valid List<PosDraftLineInput> lines
) {
}
