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
@Table(name = "item_images")
public class ItemImage {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "s3_key", nullable = false, length = 500)
    private String s3Key;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "alt_text", length = 500)
    private String altText;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

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
