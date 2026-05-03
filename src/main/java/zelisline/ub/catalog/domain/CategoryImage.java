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
@Table(name = "category_images")
public class CategoryImage {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "category_id", nullable = false, length = 36)
    private String categoryId;

    @Column(name = "s3_key", length = 512)
    private String s3Key;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider = ItemImageStorageProvider.LEGACY;

    @Column(name = "cloudinary_public_id", length = 512)
    private String cloudinaryPublicId;

    @Column(name = "secure_url", length = 2048)
    private String secureUrl;

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

    @Column(name = "bytes")
    private Long bytes;

    @Column(name = "format", length = 32)
    private String format;

    @Column(name = "asset_signature", length = 80)
    private String assetSignature;

    @Column(name = "predominant_color_hex", length = 16)
    private String predominantColorHex;

    @Column(name = "phash", length = 64)
    private String phash;

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
