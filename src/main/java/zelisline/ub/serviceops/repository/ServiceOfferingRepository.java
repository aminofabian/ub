package zelisline.ub.serviceops.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.serviceops.domain.ServiceOffering;

public interface ServiceOfferingRepository extends JpaRepository<ServiceOffering, String> {

    Optional<ServiceOffering> findByIdAndBusinessIdAndDeletedAtIsNull(String id, String businessId);

    boolean existsByBusinessIdAndNameKey(String businessId, String nameKey);

    boolean existsByBusinessIdAndNameKeyAndIdNot(String businessId, String nameKey, String id);

    @Query("""
            select s from ServiceOffering s
            where s.businessId = :businessId
              and s.deletedAt is null
              and (:categoryId is null or s.categoryId = :categoryId)
              and (:active is null or s.active = :active)
              and (:nameLike is null or s.nameKey like :nameLike escape '!')
            """)
    Page<ServiceOffering> search(
            @Param("businessId") String businessId,
            @Param("categoryId") String categoryId,
            @Param("active") Boolean active,
            @Param("nameLike") String nameLike,
            Pageable pageable
    );
}
