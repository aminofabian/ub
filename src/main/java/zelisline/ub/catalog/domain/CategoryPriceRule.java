package zelisline.ub.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "category_price_rules")
public class CategoryPriceRule {

    @EmbeddedId
    private CategoryPriceRuleId id;

    @Column(name = "precedence", nullable = false)
    private int precedence;
}
