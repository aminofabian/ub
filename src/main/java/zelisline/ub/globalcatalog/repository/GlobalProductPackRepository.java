package zelisline.ub.globalcatalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.globalcatalog.domain.GlobalProductPack;

public interface GlobalProductPackRepository extends JpaRepository<GlobalProductPack, String> {

    List<GlobalProductPack> findByCatalogIdAndStatusOrderBySortOrderAsc(String catalogId, String status);

    Optional<GlobalProductPack> findByIdAndCatalogId(String id, String catalogId);

    @Query("""
            select p.globalProductId from GlobalProductPackItem p
             where p.packId = :packId
             order by p.sortOrder asc
            """)
    List<String> findProductIdsByPackId(@Param("packId") String packId);
}
