package zelisline.ub.sales.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.sales.domain.RefundPayment;

public interface RefundPaymentRepository extends JpaRepository<RefundPayment, String> {

    List<RefundPayment> findByRefundIdOrderBySortOrderAsc(String refundId);
}
