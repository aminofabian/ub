package zelisline.ub.wholesale.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.wholesale.domain.StandingOrderLine;

public interface StandingOrderLineRepository extends JpaRepository<StandingOrderLine, String> {

    List<StandingOrderLine> findByStandingOrderId(String standingOrderId);
}
