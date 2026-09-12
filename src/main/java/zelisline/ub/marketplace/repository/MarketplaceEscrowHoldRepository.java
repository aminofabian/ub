package zelisline.ub.marketplace.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.marketplace.domain.MarketplaceEscrowHold;

public interface MarketplaceEscrowHoldRepository extends JpaRepository<MarketplaceEscrowHold, String> {

    Optional<MarketplaceEscrowHold> findByIdAndBusinessId(String id, String businessId);

    Optional<MarketplaceEscrowHold> findByBusinessIdAndPurchaseOrderIdAndStatus(
            String businessId, String purchaseOrderId, String status);

    List<MarketplaceEscrowHold> findByPurchaseOrderIdAndStatus(String purchaseOrderId, String status);

    List<MarketplaceEscrowHold> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    List<MarketplaceEscrowHold> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    Optional<MarketplaceEscrowHold> findByKopokopoSendMoneyId(String kopokopoSendMoneyId);

    Optional<MarketplaceEscrowHold> findByFundedLedgerReference(String fundedLedgerReference);
}
