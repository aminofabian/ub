package zelisline.ub.credits.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.credits.domain.WalletTransaction;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, String> {

    List<WalletTransaction> findByCreditAccountIdOrderByCreatedAtDesc(String creditAccountId);

    List<WalletTransaction> findByCreditAccountIdOrderByCreatedAtAsc(String creditAccountId);

    List<WalletTransaction> findBySaleIdOrderByCreatedAtAsc(String saleId);
}
