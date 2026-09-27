package zelisline.ub.integrations.pickupmtaani.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A short-lived delivery quote (scope §10). Durable history lives on the order
 * shipment; this row exists so checkout cannot submit a cheaper fee than we
 * quoted, and is safe to sweep after {@link #expiresAt}.
 */
@Getter
@Setter
@Entity
@Table(name = "pickup_mtaani_quotes")
public class PickupMtaaniQuote {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    /** {@code agent} or {@code doorstep}. */
    @Column(name = "mode", nullable = false, length = 16)
    private String mode;

    @Column(name = "origin_agent_id", nullable = false)
    private Long originAgentId;

    /** Destination agent id, or doorstep destination id, per mode. */
    @Column(name = "destination_id", nullable = false)
    private Long destinationId;

    @Column(name = "destination_label", length = 255)
    private String destinationLabel;

    @Column(name = "upstream_fee_kes", nullable = false, precision = 14, scale = 2)
    private BigDecimal upstreamFeeKes;

    @Column(name = "shopper_fee_kes", nullable = false, precision = 14, scale = 2)
    private BigDecimal shopperFeeKes;

    /** Snapshot of the tenant's fee mode at quote time. */
    @Column(name = "fee_mode", nullable = false, length = 24)
    private String feeMode;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
