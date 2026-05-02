package zelisline.ub.catalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.catalog.domain.Category;

public interface CategoryRepository extends JpaRepository<Category, String> {

    List<Category> findByBusinessIdOrderByPositionAsc(String businessId);

    Optional<Category> findByIdAndBusinessId(String id, String businessId);

    boolean existsByBusinessIdAndSlug(String businessId, String slug);
}
