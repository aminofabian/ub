package zelisline.ub.notifications.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.notifications.domain.NotificationEvent;

public interface NotificationEventRepository extends JpaRepository<NotificationEvent, String> {

    boolean existsByBusinessIdAndDedupeKey(String businessId, String dedupeKey);

    Optional<NotificationEvent> findByBusinessIdAndDedupeKey(String businessId, String dedupeKey);

    @Query("""
        select e from NotificationEvent e
         where e.status = 'PENDING'
         order by e.createdAt asc
        """)
    List<NotificationEvent> findPending(Pageable pageable);
}
