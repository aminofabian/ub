package zelisline.ub.serving.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import zelisline.ub.serving.domain.ServingTicketPoint;

public interface ServingTicketPointRepository extends JpaRepository<ServingTicketPoint, String> {

    List<ServingTicketPoint> findByTicketIdOrderBySeqAsc(String ticketId);

    List<ServingTicketPoint> findByTicketIdIn(Collection<String> ticketIds);

    @Modifying(clearAutomatically = true)
    @Transactional
    void deleteByTicketIdAndStatus(String ticketId, String status);
}
