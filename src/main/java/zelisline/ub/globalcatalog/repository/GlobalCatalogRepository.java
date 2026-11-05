package zelisline.ub.globalcatalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;

public interface GlobalCatalogRepository extends JpaRepository<GlobalCatalog, String> {

    Optional<GlobalCatalog> findByCode(String code);

    /**
     * Catalog-level write lock so bulk writers (CSV import, promote) cannot interleave
     * on the same catalog (GLOBAL_CATALOG_IMPORT_SCOPE §6.5).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from GlobalCatalog c where c.id = :id")
    Optional<GlobalCatalog> lockById(@Param("id") String id);

    Optional<GlobalCatalog> findByCodeAndStatus(String code, String status);

    Optional<GlobalCatalog> findFirstByRegionCodeAndStatusOrderByVersionDesc(String regionCode, String status);

    Optional<GlobalCatalog> findFirstByStatusOrderByVersionDesc(String status);

    List<GlobalCatalog> findAllByOrderByCodeAsc();
}
