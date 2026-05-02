package zelisline.ub.identity.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.identity.domain.Permission;

public interface PermissionRepository extends JpaRepository<Permission, String> {

    Optional<Permission> findByPermissionKey(String permissionKey);

    List<Permission> findAllByPermissionKeyIn(Collection<String> permissionKeys);

    /**
     * Permission keys granted to the given role via {@code role_permissions}.
     * Used by the request-scoped permission cache (PHASE_1_PLAN.md §2.2).
     */
    @Query("""
        select p.permissionKey
          from Permission p
          join RolePermission rp on rp.id.permissionId = p.id
         where rp.id.roleId = :roleId
        """)
    List<String> findPermissionKeysByRoleId(@Param("roleId") String roleId);
}
