package zelisline.ub.catalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.catalog.domain.ItemType;

public interface ItemTypeRepository extends JpaRepository<ItemType, String> {

    List<ItemType> findByBusinessIdOrderBySortOrderAsc(String businessId);

    Optional<ItemType> findByIdAndBusinessId(String id, String businessId);

    Optional<ItemType> findByBusinessIdAndTypeKey(String businessId, String typeKey);

    boolean existsByBusinessIdAndTypeKey(String businessId, String typeKey);

    Optional<ItemType> findByBusinessIdAndIsDefaultTrue(String businessId);

    @Modifying
    @Query("update ItemType t set t.isDefault = false where t.businessId = :businessId and t.isDefault = true")
    void clearDefaultForBusiness(@Param("businessId") String businessId);
}
