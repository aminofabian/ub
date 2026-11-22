package zelisline.ub.wholesale.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.wholesale.domain.SalesOrderLine;

public interface SalesOrderLineRepository extends JpaRepository<SalesOrderLine, String> {

    List<SalesOrderLine> findByOrderIdAndBusinessIdOrderByLineIndexAsc(String orderId, String businessId);

    List<SalesOrderLine> findByBusinessIdAndOrderIdIn(String businessId, java.util.Collection<String> orderIds);
}
