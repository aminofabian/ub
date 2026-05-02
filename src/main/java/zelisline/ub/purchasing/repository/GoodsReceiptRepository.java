package zelisline.ub.purchasing.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.purchasing.domain.GoodsReceipt;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, String> {

    Optional<GoodsReceipt> findByIdAndBusinessId(String id, String businessId);
}
