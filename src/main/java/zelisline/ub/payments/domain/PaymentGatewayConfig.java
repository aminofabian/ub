package zelisline.ub.payments.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Tenant-scoped gateway configuration.
 *
 * <p>Each business may have at most one config per {@link GatewayType}
 * (enforced by {@code UNIQUE (business_id, gateway_type)}). The
 * {@link #credentialsJson} is encrypted at rest with AES-256-GCM.
 *
 * <p>Lifecycle is driven by {@link GatewayStatus}:
 * {@code DRAFT → TESTING → TESTED → ACTIVE}, with {@code ERROR} as
 * a recoverable terminal state.
 */
@Getter
@Setter
@Entity
@Table(name = "payment_gateway_configs")
public class PaymentGatewayConfig {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Enumerated(EnumType.STRING)
    @Column(name = "gateway_type", nullable = false, length = 32)
    private GatewayType gatewayType;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GatewayStatus status = GatewayStatus.DRAFT;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "credentials_json", columnDefinition = "text")
    private String credentialsJson;

    @Column(name = "display_instructions_json", columnDefinition = "text")
    private String displayInstructionsJson;

    @Column(name = "last_tested_at")
    private Instant lastTestedAt;

    @Column(name = "test_error_json", columnDefinition = "text")
    private String testErrorJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // ---- convenience queries ----

    public boolean isActive() {
        return status == GatewayStatus.ACTIVE;
    }

    public boolean isTested() {
        return status == GatewayStatus.TESTED;
    }

    public boolean canActivate() {
        return status == GatewayStatus.TESTED;
    }

    public boolean canTest() {
        return status == GatewayStatus.DRAFT || status == GatewayStatus.ERROR;
    }
}
