package zelisline.ub.discounts.domain;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "discount_items")
public class DiscountItem {

    @EmbeddedId
    private DiscountItemId id;

    @Embeddable
    @Getter
    @Setter
    @EqualsAndHashCode
    public static class DiscountItemId implements Serializable {
        @Column(name = "discount_id", nullable = false, length = 36)
        private String discountId;

        @Column(name = "item_id", nullable = false, length = 36)
        private String itemId;
    }
}
