package zelisline.ub.serviceops.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A staff profile that may perform a service. No rows for a service means anyone may. */
@Getter
@Setter
@Entity
@Table(name = "service_staff")
public class ServiceStaffLink {

    @EmbeddedId
    private Id id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id implements Serializable {

        @Column(name = "service_id", nullable = false, length = 36)
        private String serviceId;

        @Column(name = "staff_profile_id", nullable = false, length = 36)
        private String staffProfileId;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Id that)) {
                return false;
            }
            return Objects.equals(serviceId, that.serviceId)
                    && Objects.equals(staffProfileId, that.staffProfileId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(serviceId, staffProfileId);
        }
    }
}
