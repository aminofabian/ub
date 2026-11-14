package zelisline.ub.serviceops.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class PackageDtos {

    private PackageDtos() {
    }

    /** Sets, or with null sessions removes, the prepaid package offered for a service. */
    public record SetPackageOfferRequest(
            @Min(2) @Max(100) Integer sessions,
            @DecimalMin("1") BigDecimal price,
            @Min(1) @Max(3650) Integer validDays
    ) {
    }

    public record PackageOfferResponse(
            String serviceId,
            String serviceName,
            /** The price of one visit without a package. */
            BigDecimal singlePrice,
            int sessions,
            BigDecimal price,
            Integer validDays
    ) {
    }

    public record SellPackageRequest(
            @NotBlank @Size(max = 36) String branchId,
            @NotBlank @Size(max = 36) String customerId,
            @NotBlank @Size(max = 36) String serviceId,
            /** {@code cash} or {@code mpesa_manual}. */
            @NotBlank @Size(max = 32) String method,
            @Size(max = 64) String reference
    ) {
    }

    public record CustomerPackageResponse(
            String id,
            String customerId,
            String serviceId,
            String serviceName,
            int sessionsTotal,
            int sessionsRemaining,
            BigDecimal pricePaid,
            BigDecimal valueRemaining,
            Instant expiresAt,
            boolean expired,
            Instant soldAt
    ) {
    }
}
