package zelisline.ub.tenancy.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.tenancy.domain.DomainMapping;

public interface DomainMappingRepository extends JpaRepository<DomainMapping, String> {
    Optional<DomainMapping> findByDomainAndActiveTrue(String domain);

    List<DomainMapping> findByBusinessIdAndDeletedAtIsNull(String businessId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update DomainMapping d
           set d.deletedAt = :ts,
               d.updatedAt = :ts
         where d.businessId = :businessId
           and d.deletedAt is null
        """)
    int softDeleteAllByBusinessId(@Param("businessId") String businessId, @Param("ts") Instant ts);
}
