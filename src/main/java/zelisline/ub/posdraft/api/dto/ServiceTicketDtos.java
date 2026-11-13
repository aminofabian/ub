package zelisline.ub.posdraft.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import zelisline.ub.sales.api.dto.PostSalePaymentRequest;
import zelisline.ub.sales.api.dto.PostSaleTipRequest;
import zelisline.ub.sales.api.dto.SaleResponse;

/** Shapes for walk-in service tickets — the queue of a service business (ADR 0014). */
public final class ServiceTicketDtos {

    private ServiceTicketDtos() {
    }

    public record TicketLineInput(
            @NotBlank @Size(max = 36) String serviceId,
            /** Price for a variable-price service; ignored for a fixed-price one. */
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal unitPrice
    ) {
    }

    public record CreateServiceTicketRequest(
            @NotBlank @Size(max = 36) String branchId,
            /** Made up once by the client; sending it again returns the same ticket. */
            @NotBlank @Size(max = 64) String clientTicketId,
            @Size(max = 36) String customerId,
            /** The staff member the customer asked for; null means next available. */
            @Size(max = 36) String requestedStaffProfileId,
            @NotEmpty @Size(max = 20) @Valid List<TicketLineInput> lines
    ) {
    }

    public record StartTicketRequest(
            @NotBlank @Size(max = 36) String performerStaffProfileId,
            @NotNull @Min(1) @Max(50) Integer stationNo
    ) {
    }

    public record CancelTicketRequest(
            @Size(max = 500) String reason,
            /** True when the customer walked away before being served or paying. */
            Boolean abandoned
    ) {
    }

    public record PayTicketRequest(
            @NotEmpty @Valid List<PostSalePaymentRequest> payments,
            @Valid List<PostSaleTipRequest> tips,
            @DecimalMin("0") BigDecimal cashReceived,
            Instant clientSoldAt
    ) {
    }

    public record TicketLineResponse(
            String id,
            String serviceId,
            String name,
            BigDecimal unitPrice,
            BigDecimal lineTotal
    ) {
    }

    public record TicketResponse(
            String id,
            String branchId,
            long ticketNumber,
            /** {@code WAITING}, {@code IN_SERVICE}, {@code READY_TO_PAY}; closed tickets are not listed. */
            String stage,
            String status,
            Integer stationNo,
            String staffProfileId,
            String staffName,
            String customerId,
            BigDecimal total,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            String saleId,
            long version,
            List<TicketLineResponse> lines
    ) {
    }

    public record ServiceBoardResponse(
            String branchId,
            /** Number of chairs at the branch (1..N). */
            int stationCount,
            List<TicketResponse> tickets
    ) {
    }

    public record PaidTicketResponse(TicketResponse ticket, SaleResponse sale) {
    }
}
