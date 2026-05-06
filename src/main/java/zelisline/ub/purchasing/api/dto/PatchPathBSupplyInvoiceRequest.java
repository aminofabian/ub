package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PatchPathBSupplyInvoiceRequest(
        @NotBlank @Size(max = 64) String invoiceNumber,
        @NotNull LocalDate invoiceDate,
        LocalDate dueDate,
        @Size(max = 20000) String notes,
        @Valid List<PatchPathBSupplyInvoiceLineRequest> lines
) {
}
