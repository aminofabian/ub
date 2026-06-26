package zelisline.ub.globalcatalog.domain;

import java.io.Serializable;
import java.math.BigDecimal;

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
@Table(name = "global_product_supplier_links")
@IdClass(GlobalProductSupplierLink.Pk.class)
public class GlobalProductSupplierLink {

    @Id
    @Column(name = "global_product_id", length = 36)
    private String globalProductId;

    @Id
    @Column(name = "global_supplier_template_id", length = 36)
    private String globalSupplierTemplateId;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "default_cost_price", precision = 14, scale = 4)
    private BigDecimal defaultCostPrice;

    @Column(name = "supplier_sku", length = 191)
    private String supplierSku;

    @Getter
    @Setter
    public static class Pk implements Serializable {
        private String globalProductId;
        private String globalSupplierTemplateId;
    }
}
