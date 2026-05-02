package zelisline.ub.purchasing.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record PostPathBRequest(@NotEmpty @Valid List<PostPathBLineBreakdown> lines) {
}
