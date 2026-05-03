package zelisline.ub.catalog.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.catalog.domain.CategoryPriceRule;
import zelisline.ub.catalog.domain.CategoryPriceRuleId;

public interface CategoryPriceRuleRepository extends JpaRepository<CategoryPriceRule, CategoryPriceRuleId> {

    @Query("SELECT r FROM CategoryPriceRule r WHERE r.id.categoryId = :cid ORDER BY r.precedence ASC, r.id.priceRuleId ASC")
    List<CategoryPriceRule> findByCategoryOrdered(@Param("cid") String categoryId);

    @Modifying
    @Query("DELETE FROM CategoryPriceRule r WHERE r.id.categoryId = :cid AND r.id.priceRuleId = :rid")
    void deleteByCategoryAndRule(@Param("cid") String categoryId, @Param("rid") String ruleId);
}
