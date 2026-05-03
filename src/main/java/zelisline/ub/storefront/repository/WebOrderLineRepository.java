package zelisline.ub.storefront.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.storefront.domain.WebOrderLine;

public interface WebOrderLineRepository extends JpaRepository<WebOrderLine, String> {

    List<WebOrderLine> findByOrderIdOrderByLineIndexAsc(String orderId);
}
