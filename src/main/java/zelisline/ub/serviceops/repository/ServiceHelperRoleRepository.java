package zelisline.ub.serviceops.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.serviceops.domain.ServiceHelperRole;

public interface ServiceHelperRoleRepository extends JpaRepository<ServiceHelperRole, String> {

    List<ServiceHelperRole> findByBusinessIdOrderBySortOrderAscNameAsc(String businessId);

    Optional<ServiceHelperRole> findByIdAndBusinessId(String id, String businessId);
}
