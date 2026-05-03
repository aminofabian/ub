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

    /**
     * Clears the current primary for a business (keeps the promoted row untouched).
     *
     * <p>Must run before the promoted row is written so that the generated
     * {@code primary_business_id} unique index (see {@code V1__tenancy_core.sql})
     * does not see two rows carrying the same business id at flush time.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update DomainMapping d
           set d.primary = false,
               d.updatedAt = :ts
         where d.businessId = :businessId
           and d.id <> :promotedId
           and d.primary = true
           and d.deletedAt is null
        """)
    int clearPrimaryForBusinessExcept(
            @Param("businessId") String businessId,
            @Param("promotedId") String promotedId,
            @Param("ts") Instant ts);
}
