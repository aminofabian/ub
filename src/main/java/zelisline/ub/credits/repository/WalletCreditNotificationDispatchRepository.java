package zelisline.ub.credits.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.credits.domain.WalletCreditNotificationDispatch;

public interface WalletCreditNotificationDispatchRepository
        extends JpaRepository<WalletCreditNotificationDispatch, String> {

    boolean existsBySaleId(String saleId);
}
