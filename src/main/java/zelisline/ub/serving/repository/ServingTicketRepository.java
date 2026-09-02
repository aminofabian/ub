package zelisline.ub.serving.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.serving.domain.ServingTicket;

public interface ServingTicketRepository extends JpaRepository<ServingTicket, String> {

    List<ServingTicket> findByConversationIdOrderByCreatedAtDesc(String conversationId);

    Optional<ServingTicket> findByContactMessageId(String contactMessageId);

    Optional<ServingTicket> findByTicketNumber(int ticketNumber);

    Optional<ServingTicket> findTopByOrderByTicketNumberDesc();

    List<ServingTicket> findAllByOrderByUpdatedAtDesc();

    List<ServingTicket> findByBusinessIdOrderByCreatedAtAsc(String businessId);

    long countByAssignedToAndStatusIn(String assignedTo, Collection<String> statuses);

    long countByAssignedToIsNullAndStatus(String status);
}
