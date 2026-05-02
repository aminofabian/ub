package zelisline.ub.catalog.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "key_hash", nullable = false, length = 64)
    private String keyHash;

    @Column(name = "route", nullable = false, length = 128)
    private String route;

    @Column(name = "body_hash", nullable = false, length = 64)
    private String bodyHash;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    @Column(name = "response_json", nullable = false, columnDefinition = "TEXT")
    private String responseJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
