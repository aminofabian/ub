package zelisline.ub.storeroom.domain;

import java.time.Instant;

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
 * Per-business store-room connection choice. One row per business, created lazily
 * the first time a merchant answers the standalone-vs-connected prompt.
 */
@Getter
@Setter
@Entity
@Table(name = "store_room_settings")
public class StoreRoomSettings {

    @Id
    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    /** {@code null} until the merchant chooses. */
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", length = 16)
    private StoreRoomMode mode;

    @Column(name = "connected_at")
    private Instant connectedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
