package zelisline.ub.globalcatalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.globalcatalog.domain.GlobalSupplierTemplate;

public interface GlobalSupplierTemplateRepository extends JpaRepository<GlobalSupplierTemplate, String> {

    List<GlobalSupplierTemplate> findByCatalogIdOrderByNameAsc(String catalogId);

    Optional<GlobalSupplierTemplate> findByIdAndCatalogId(String id, String catalogId);

    Optional<GlobalSupplierTemplate> findByCatalogIdAndCode(String catalogId, String code);

    boolean existsByCatalogIdAndCode(String catalogId, String code);
}
