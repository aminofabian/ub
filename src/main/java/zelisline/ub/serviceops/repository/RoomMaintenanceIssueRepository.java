package zelisline.ub.serviceops.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.serviceops.domain.RoomMaintenanceIssue;

public interface RoomMaintenanceIssueRepository extends JpaRepository<RoomMaintenanceIssue, String> {

    /** A branch's issues, newest first — for the front-desk maintenance list. */
    List<RoomMaintenanceIssue> findByBusinessIdAndBranchIdOrderByOpenedAtDesc(String businessId, String branchId);

    /** A branch's issues in the given statuses (e.g. OPEN + IN_PROGRESS), newest first. */
    List<RoomMaintenanceIssue> findByBusinessIdAndBranchIdAndStatusInOrderByOpenedAtDesc(
            String businessId, String branchId, List<String> statuses);

    /** One room's history, newest first. */
    List<RoomMaintenanceIssue> findByBusinessIdAndRoomResourceIdOrderByOpenedAtDesc(
            String businessId, String roomResourceId);

    /** Tenant-scoped fetch for an update; never load by id alone. */
    Optional<RoomMaintenanceIssue> findByIdAndBusinessId(String id, String businessId);

    /** How many issues on a room are not yet DONE — drives the front-desk board badge. */
    long countByBusinessIdAndRoomResourceIdAndStatusNot(String businessId, String roomResourceId, String status);
}
