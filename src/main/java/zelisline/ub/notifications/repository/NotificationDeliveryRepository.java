package zelisline.ub.notifications.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.notifications.domain.NotificationDelivery;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, String> {

    @Query("""
        select d from NotificationDelivery d
         where d.status = 'PENDING'
           and (d.nextRetryAt is null or d.nextRetryAt <= :now)
         order by d.createdAt asc
        """)
    List<NotificationDelivery> findDuePending(@Param("now") Instant now, Pageable pageable);
}
