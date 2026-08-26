package zelisline.ub.integrations.metacapi.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One durable Meta Conversions API event (CompleteRegistration / Purchase).
 * Also serves as the restricted super-admin delivery audit: {@link #requestJson}
 * and {@link #responseJson} retain the full request/response for troubleshooting
 * and are only exposed under {@code /api/v1/super-admin/meta-capi/events}.
 *
 * <p>The Authorization header is never stored; the encrypted tenant access token
 * is decrypted fresh at send time from {@code businesses.settings.metaCapi}.
 */
@Getter
@Setter
@Entity
@Table(name = "meta_capi_events")
public class MetaCapiEvent {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "pixel_id", nullable = false, length = 64)
    private String pixelId;

    @Column(name = "event_name", nullable = false, length = 64)
    private String eventName;

    /** Stable event id; must match the browser Pixel eventID byte-for-byte. */
    @Column(name = "event_id", nullable = false, length = 128)
    private String eventId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "request_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String requestJson;

    @Column(name = "response_json", columnDefinition = "MEDIUMTEXT")
    private String responseJson;

    @Column(name = "error", length = 1024)
    private String error;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null || status.isBlank()) {
            status = MetaCapiEventStatuses.PENDING;
        }
    }
}
