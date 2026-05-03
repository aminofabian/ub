package zelisline.ub.catalog.domain;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CategoryPriceRuleId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "category_id", nullable = false, length = 36)
    private String categoryId;

    @Column(name = "price_rule_id", nullable = false, length = 36)
    private String priceRuleId;
}
