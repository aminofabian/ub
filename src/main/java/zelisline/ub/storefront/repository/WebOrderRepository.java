package zelisline.ub.storefront.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.storefront.domain.WebOrder;

public interface WebOrderRepository extends JpaRepository<WebOrder, String> {

    Page<WebOrder> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    Optional<WebOrder> findByIdAndBusinessId(String id, String businessId);
}
