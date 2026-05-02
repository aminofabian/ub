package zelisline.ub.identity.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.identity.domain.RolePermission;

public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermission.Id> {

    List<RolePermission> findByIdRoleId(String roleId);

    void deleteByIdRoleId(String roleId);
}
