package zelisline.ub.serving.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import zelisline.ub.serving.domain.ServingTicketCounter;

public interface ServingTicketCounterRepository extends JpaRepository<ServingTicketCounter, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ServingTicketCounter c WHERE c.id = :id")
    ServingTicketCounter lockById(@Param("id") String id);
}
