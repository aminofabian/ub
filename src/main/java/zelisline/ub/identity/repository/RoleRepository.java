package zelisline.ub.identity.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.identity.domain.Role;

public interface RoleRepository extends JpaRepository<Role, String> {

    /** System role lookup (those with {@code business_id IS NULL}). */
    @Query("""
        select r from Role r
         where r.businessId is null
           and r.roleKey = :roleKey
           and r.deletedAt is null
        """)
    Optional<Role> findSystemRoleByKey(@Param("roleKey") String roleKey);

    /**
     * Tenant-scoped role lookup. Falls back to {@link #findSystemRoleByKey(String)}
     * by composition where callers want either path; we keep the queries explicit
     * so service code is unambiguous about which scope it is asking for.
     */
    @Query("""
        select r from Role r
         where r.businessId = :businessId
           and r.roleKey = :roleKey
           and r.deletedAt is null
        """)
    Optional<Role> findTenantRoleByKey(
            @Param("businessId") String businessId,
            @Param("roleKey") String roleKey);

    /** Visible roles for a tenant: system roles + the tenant's own. */
    @Query("""
        select r from Role r
         where (r.businessId is null or r.businessId = :businessId)
           and r.deletedAt is null
        """)
    List<Role> findVisibleForTenant(@Param("businessId") String businessId);

    Optional<Role> findByIdAndDeletedAtIsNull(String id);
}
