package zelisline.ub.purchasing.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.purchasing.domain.SupplierPayment;

public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, String> {
}
