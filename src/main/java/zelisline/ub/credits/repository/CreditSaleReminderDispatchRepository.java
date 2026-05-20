package zelisline.ub.credits.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.credits.domain.CreditSaleReminderDispatch;

public interface CreditSaleReminderDispatchRepository extends JpaRepository<CreditSaleReminderDispatch, String> {

    boolean existsBySaleId(String saleId);
}
