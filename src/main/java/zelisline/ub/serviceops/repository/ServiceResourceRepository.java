package zelisline.ub.serviceops.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import zelisline.ub.serviceops.domain.ServiceResource;

public interface ServiceResourceRepository extends JpaRepository<ServiceResource, String> {

    List<ServiceResource> findByBusinessIdAndBranchIdOrderBySortOrderAscNameAsc(String businessId, String branchId);

    Optional<ServiceResource> findByIdAndBusinessId(String id, String businessId);

    Optional<ServiceResource> findByBusinessIdAndBranchIdAndStationNo(String businessId, String branchId, Integer stationNo);

    /** Locked, so two tills starting a service on one chair take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ServiceResource r where r.id = :id and r.businessId = :businessId")
    Optional<ServiceResource> findByIdAndBusinessIdForUpdate(
            @Param("id") String id, @Param("businessId") String businessId);
}
