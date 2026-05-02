package zelisline.ub.identity.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.identity.domain.User;

public interface UserRepository extends JpaRepository<User, String> {

    @Query("""
        select u.id from User u
         where u.businessId = :businessId
           and u.deletedAt is null
        """)
    List<String> findIdsByBusinessIdAndDeletedAtIsNull(@Param("businessId") String businessId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update User u
           set u.deletedAt = :ts,
               u.updatedAt = :ts
         where u.businessId = :businessId
           and u.deletedAt is null
        """)
    int softDeleteAllByBusinessId(@Param("businessId") String businessId, @Param("ts") Instant ts);

    /**
     * Tenant-scoped lookup. Returns empty for users that belong to a different
     * tenant — the caller must NEVER fall back to a global lookup; cross-tenant
     * leakage is a Slice 2 invariant (PHASE_1_PLAN.md §2.4).
     */
    Optional<User> findByIdAndBusinessIdAndDeletedAtIsNull(String id, String businessId);

    Optional<User> findByBusinessIdAndEmailAndDeletedAtIsNull(String businessId, String email);

    boolean existsByBusinessIdAndEmailAndDeletedAtIsNull(String businessId, String email);

    long countByBusinessIdAndDeletedAtIsNull(String businessId);

    @Query("""
        select u from User u
         where u.businessId = :businessId
           and u.deletedAt is null
           and (:status is null or u.status = :status)
           and (:roleId is null or u.roleId = :roleId)
           and (:branchId is null or u.branchId = :branchId)
        """)
    Page<User> pageByBusinessFiltered(
            @Param("businessId") String businessId,
            @Param("status") String status,
            @Param("roleId") String roleId,
            @Param("branchId") String branchId,
            Pageable pageable
    );

    @Query("""
        select u from User u
         where u.businessId = :businessId
           and u.deletedAt is null
        """)
    Page<User> pageByBusiness(@Param("businessId") String businessId, Pageable pageable);

    /** Owner-count guard for the "cannot demote last owner" invariant (§2.4). */
    @Query("""
        select count(u)
          from User u
         where u.businessId = :businessId
           and u.roleId = :roleId
           and u.deletedAt is null
           and u.status = 'active'
        """)
    long countActiveByRoleId(
            @Param("businessId") String businessId,
            @Param("roleId") String roleId);

    /**
     * Counts active users of a tenant whose role has the given key. Used by the
     * last-owner guard to honour both the system {@code owner} role and any
     * tenant-scoped role that re-uses the {@code owner} key.
     */
    @Query("""
        select count(u)
          from User u
         where u.businessId = :businessId
           and u.deletedAt is null
           and u.status = 'active'
           and u.roleId in (select r.id from Role r where r.roleKey = :roleKey)
        """)
    long countActiveByRoleKey(
            @Param("businessId") String businessId,
            @Param("roleKey") String roleKey);
}
