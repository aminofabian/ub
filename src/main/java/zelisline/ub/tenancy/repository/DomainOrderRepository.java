package zelisline.ub.tenancy.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.tenancy.domain.DomainOrder;
import zelisline.ub.tenancy.domain.DomainOrderStatus;

public interface DomainOrderRepository extends JpaRepository<DomainOrder, String> {

    List<DomainOrder> findByBusinessIdAndDeletedAtIsNullOrderByCreatedAtDesc(String businessId);

    Optional<DomainOrder> findByIdAndBusinessIdAndDeletedAtIsNull(String id, String businessId);

    Optional<DomainOrder> findFirstByFqdnAndDeletedAtIsNullOrderByCreatedAtDesc(String fqdn);

    @Query("""
        select o from DomainOrder o
         where o.deletedAt is null
           and o.status in :statuses
         order by o.updatedAt asc
        """)
    List<DomainOrder> findOpenByStatuses(@Param("statuses") Collection<DomainOrderStatus> statuses);

    List<DomainOrder> findByDeletedAtIsNullOrderByUpdatedAtDesc();

    List<DomainOrder> findByDeletedAtIsNullAndStatusOrderByUpdatedAtDesc(DomainOrderStatus status);
}
