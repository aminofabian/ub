package zelisline.ub.tenancy.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.billing.domain.SubscriptionBillingStatus;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.TenantStatus;

public interface BusinessRepository extends JpaRepository<Business, String> {
    @Query(value = "SELECT settings FROM businesses WHERE id = :id", nativeQuery = true)
    Optional<String> findSettingsJsonById(@Param("id") String id);

    /**
     * Light-weight projection used by the host-resolver filter to gate
     * SUSPENDED/INACTIVE tenants without loading the full {@link Business}.
     */
    @Query("select b.tenantStatus from Business b where b.id = :id and b.deletedAt is null")
    Optional<TenantStatus> findTenantStatusById(@Param("id") String id);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndDeletedAtIsNull(String slug);

    Optional<Business> findBySlug(String slug);

    Optional<Business> findBySlugAndDeletedAtIsNull(String slug);

    Optional<Business> findFirstByNameIgnoreCaseAndDeletedAtIsNull(String name);

    java.util.List<Business> findBySlugStartingWithAndDeletedAtIsNull(String slugPrefix);

    /** Apex shop directory search: fuzzy name match, capped (Phase 4). */
    java.util.List<Business> findTop8ByDeletedAtIsNullAndNameContainingIgnoreCaseOrderByNameAsc(String fragment);

    /** Apex shop directory search: slug prefix match, capped (Phase 4). */
    java.util.List<Business> findTop8ByDeletedAtIsNullAndSlugStartingWithOrderBySlugAsc(String slugPrefix);

    Optional<Business> findByIdAndDeletedAtIsNull(String id);

    java.util.List<Business> findByDeletedAtIsNull();

    Page<Business> findByDeletedAtIsNull(Pageable pageable);

    long countByDeletedAtIsNull();

    long countByDeletedAtIsNullAndActiveTrue();

    long countByDeletedAtIsNullAndCreatedAtGreaterThanEqual(java.time.Instant since);

    java.util.List<Business> findTop12ByDeletedAtIsNullOrderByCreatedAtDesc();

    /** Name lookup for the platform request log — resolves tenant names from ids in bulk. */
    @Query("select b.id as id, b.name as name from Business b where b.id in :ids")
    java.util.List<BusinessNameRow> findNamesByIds(@Param("ids") java.util.Collection<String> ids);

    interface BusinessNameRow {
        String getId();

        String getName();
    }

    @Query("""
        select b from Business b
         where b.deletedAt is null
           and b.subscriptionBillingStatus = :status
           and b.currentPeriodEnd is not null
           and b.currentPeriodEnd <= :now
        """)
    java.util.List<Business> findDueForPeriodExpiry(
            @Param("status") SubscriptionBillingStatus status,
            @Param("now") java.time.Instant now);

    @Query("""
        select b from Business b
         where b.deletedAt is null
           and b.subscriptionBillingStatus = :grace
           and b.graceEndsAt is not null
           and b.graceEndsAt <= :now
        """)
    java.util.List<Business> findDueForGraceEnd(
            @Param("grace") SubscriptionBillingStatus grace,
            @Param("now") java.time.Instant now);

    @Query("""
        select b from Business b
         where b.deletedAt is null
           and b.subscriptionBillingStatus = zelisline.ub.billing.domain.SubscriptionBillingStatus.ACTIVE
           and b.currentPeriodEnd is not null
           and b.currentPeriodEnd >= :windowStart
           and b.currentPeriodEnd < :windowEnd
           and lower(b.subscriptionTier) <> 'free'
        """)
    java.util.List<Business> findDueForPreExpiryReminder(
            @Param("windowStart") java.time.Instant windowStart,
            @Param("windowEnd") java.time.Instant windowEnd);

    @Query("""
        select count(b) from Business b
         where b.deletedAt is null
           and b.subscriptionBillingStatus = :status
           and lower(b.subscriptionTier) <> 'free'
        """)
    long countPaidTierByBillingStatus(@Param("status") SubscriptionBillingStatus status);

    @Query("""
        select b from Business b
         where b.deletedAt is null
           and b.subscriptionBillingStatus = :status
           and lower(b.subscriptionTier) <> 'free'
        """)
    java.util.List<Business> findPaidTierByBillingStatus(@Param("status") SubscriptionBillingStatus status);
}
