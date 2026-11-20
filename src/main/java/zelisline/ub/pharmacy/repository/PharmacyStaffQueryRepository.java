package zelisline.ub.pharmacy.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import zelisline.ub.identity.domain.User;

/** Which staff of a business hold a given permission (through their role). */
public interface PharmacyStaffQueryRepository extends Repository<User, String> {

    @Query("""
            select u
              from User u
             where u.businessId = :businessId
               and u.deletedAt is null
               and u.status = 'active'
               and u.roleId in (
                   select rp.id.roleId
                     from RolePermission rp
                     join Permission p on p.id = rp.id.permissionId
                    where p.permissionKey = :permissionKey)
             order by u.name asc, u.id asc
            """)
    List<User> findActiveUsersWithPermission(
            @Param("businessId") String businessId,
            @Param("permissionKey") String permissionKey
    );
}
