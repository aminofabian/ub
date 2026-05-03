package zelisline.ub.catalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.catalog.domain.CategorySupplierLink;

public interface CategorySupplierLinkRepository extends JpaRepository<CategorySupplierLink, String> {

    List<CategorySupplierLink> findByBusinessIdOrderByCategoryIdAscSortOrderAscSupplierIdAsc(String businessId);

    List<CategorySupplierLink> findByBusinessIdAndCategoryIdOrderBySortOrderAscSupplierIdAsc(
            String businessId,
            String categoryId
    );

    Optional<CategorySupplierLink> findByBusinessIdAndCategoryIdAndSupplierId(
            String businessId,
            String categoryId,
            String supplierId
    );

    boolean existsByBusinessIdAndCategoryIdAndSupplierId(String businessId, String categoryId, String supplierId);
}
