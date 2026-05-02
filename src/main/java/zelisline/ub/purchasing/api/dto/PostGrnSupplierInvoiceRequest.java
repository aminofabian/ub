package zelisline.ub.purchasing.api.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record PostGrnSupplierInvoiceRequest(
        @NotBlank String invoiceNumber,
        @NotNull LocalDate invoiceDate,
        LocalDate dueDate,
        @NotEmpty @Valid List<PostGrnSupplierInvoiceLineInput> lines
) {
}
