package zelisline.ub.serving.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.serving.domain.ServingTicketNote;

public interface ServingTicketNoteRepository extends JpaRepository<ServingTicketNote, String> {

    List<ServingTicketNote> findByTicketIdOrderByCreatedAtAsc(String ticketId);
}
