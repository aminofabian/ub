package zelisline.ub.serving.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.serving.domain.ServingTicketEvent;

public interface ServingTicketEventRepository extends JpaRepository<ServingTicketEvent, String> {

    List<ServingTicketEvent> findByTicketIdOrderByCreatedAtAsc(String ticketId);
}
