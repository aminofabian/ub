package zelisline.ub.sales.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.sales.domain.SalePayment;

public interface SalePaymentRepository extends JpaRepository<SalePayment, String> {

    List<SalePayment> findBySaleIdOrderBySortOrderAsc(String saleId);
}
