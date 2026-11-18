package zelisline.ub.serviceops.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.serviceops.domain.RoomType;

public interface RoomTypeRepository extends JpaRepository<RoomType, String> {

    List<RoomType> findByBusinessIdAndBranchIdOrderBySortOrderAscNameAsc(String businessId, String branchId);

    Optional<RoomType> findByIdAndBusinessId(String id, String businessId);

    long countByBusinessIdAndBranchId(String businessId, String branchId);
}
