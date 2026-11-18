package zelisline.ub.platform.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Platform-global presentation for one business-type (store-format) code — the row Super Admin
 * edits. See ADR 0029: the type <em>codes</em> stay a fixed, code-defined enum
 * ({@code StoreTypeCodes}); only this presentation metadata is data. The read model overlays this
 * row onto the built-in defaults ({@code BusinessTypeCatalogDefaults}) by code, so a missing row
 * still renders its default label/hint/group and a row for an unknown code is ignored.
 *
 * <p>Not tenant-scoped: the catalogue is the same for every shop.
 */
@Entity
@Table(name = "business_type_catalog")
@Getter
@Setter
public class BusinessTypeCatalogEntry {

    /** The store-format code, e.g. {@code bar}, {@code salon}, {@code lodging}. Also the primary key. */
    @Id
    @Column(name = "code", length = 64, nullable = false)
    private String code;

    @Column(name = "label", length = 120, nullable = false)
    private String label;

    @Column(name = "hint", length = 400)
    private String hint;

    /** Display section: {@code food}, {@code service}, {@code lodging}, {@code shop}. */
    @Column(name = "section", length = 32, nullable = false)
    private String section;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** Secure URL of the type image (R2/Cloudinary), served verbatim. Null until one is uploaded. */
    @Column(name = "image_url", length = 1024)
    private String imageUrl;

    /** Provider public id for the uploaded image, kept so an old image can be destroyed on replace. */
    @Column(name = "image_public_id", length = 512)
    private String imagePublicId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
