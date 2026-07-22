package zelisline.ub.globalcatalog.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.globalcatalog.domain.GlobalProductPackItem;

public interface GlobalProductPackItemRepository extends JpaRepository<GlobalProductPackItem, GlobalProductPackItem.Pk> {

    List<GlobalProductPackItem> findByPackIdOrderBySortOrderAsc(String packId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from GlobalProductPackItem p where p.packId = :packId")
    void deleteByPackId(@Param("packId") String packId);
}
