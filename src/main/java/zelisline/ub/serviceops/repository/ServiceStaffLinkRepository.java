package zelisline.ub.serviceops.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.serviceops.domain.ServiceStaffLink;

public interface ServiceStaffLinkRepository extends JpaRepository<ServiceStaffLink, ServiceStaffLink.Id> {

    @Query("""
            select l from ServiceStaffLink l
            where l.businessId = :businessId and l.id.serviceId in :serviceIds
            """)
    List<ServiceStaffLink> findByBusinessIdAndServiceIdIn(
            @Param("businessId") String businessId,
            @Param("serviceIds") Collection<String> serviceIds
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from ServiceStaffLink l
            where l.businessId = :businessId and l.id.serviceId = :serviceId
            """)
    void deleteByBusinessIdAndServiceId(
            @Param("businessId") String businessId,
            @Param("serviceId") String serviceId
    );
}
