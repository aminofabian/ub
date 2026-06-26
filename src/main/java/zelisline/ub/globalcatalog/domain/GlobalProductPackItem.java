package zelisline.ub.globalcatalog.domain;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "global_product_pack_items")
@IdClass(GlobalProductPackItem.Pk.class)
public class GlobalProductPackItem {

    @Id
    @Column(name = "pack_id", length = 36)
    private String packId;

    @Id
    @Column(name = "global_product_id", length = 36)
    private String globalProductId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Getter
    @Setter
    public static class Pk implements Serializable {
        private String packId;
        private String globalProductId;
    }
}
