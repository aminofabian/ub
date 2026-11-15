package zelisline.ub.restaurant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.restaurant.domain.KitchenTicketLine;

public interface KitchenTicketLineRepository extends JpaRepository<KitchenTicketLine, String> {

    Optional<KitchenTicketLine> findByIdAndBusinessId(String id, String businessId);

    List<KitchenTicketLine> findByBusinessIdAndTicketIdInOrderByLineIndexAsc(String businessId, Collection<String> ticketIds);

    List<KitchenTicketLine> findByBusinessIdAndOrderLineIdIn(String businessId, Collection<String> orderLineIds);
}
