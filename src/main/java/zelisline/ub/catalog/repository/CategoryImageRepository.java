package zelisline.ub.catalog.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.catalog.domain.CategoryImage;

public interface CategoryImageRepository extends JpaRepository<CategoryImage, String> {

    List<CategoryImage> findByCategoryIdIn(Collection<String> categoryIds, Sort sort);

    List<CategoryImage> findByCategoryIdOrderBySortOrderAscIdAsc(String categoryId);

    Optional<CategoryImage> findByIdAndCategoryId(String id, String categoryId);

    @Query("SELECT COALESCE(MAX(i.sortOrder), -1) FROM CategoryImage i WHERE i.categoryId = :categoryId")
    int maxSortOrderForCategory(@Param("categoryId") String categoryId);
}
