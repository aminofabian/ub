package zelisline.ub.globalcatalog.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.globalcatalog.domain.GlobalProductImage;

public interface GlobalProductImageRepository extends JpaRepository<GlobalProductImage, String> {

    List<GlobalProductImage> findByGlobalProductIdOrderBySortOrderAscIdAsc(String globalProductId);

    List<GlobalProductImage> findByGlobalProductIdInOrderBySortOrderAscIdAsc(Collection<String> globalProductIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from GlobalProductImage i where i.globalProductId = :productId")
    void deleteByGlobalProductId(@Param("productId") String productId);
}
