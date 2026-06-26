package zelisline.ub.globalcatalog.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.globalcatalog.domain.GlobalCatalog;

public interface GlobalCatalogRepository extends JpaRepository<GlobalCatalog, String> {

    Optional<GlobalCatalog> findByCode(String code);

    Optional<GlobalCatalog> findFirstByRegionCodeAndStatusOrderByVersionDesc(String regionCode, String status);

    Optional<GlobalCatalog> findFirstByStatusOrderByVersionDesc(String status);
}
