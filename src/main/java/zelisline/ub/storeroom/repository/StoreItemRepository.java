package zelisline.ub.storeroom.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.storeroom.domain.StoreItem;

public interface StoreItemRepository extends JpaRepository<StoreItem, String> {

    List<StoreItem> findByBusinessIdOrderByNameAsc(String businessId);

    Optional<StoreItem> findByIdAndBusinessId(String id, String businessId);

    boolean existsByBusinessIdAndBarcode(String businessId, String barcode);

    boolean existsByBusinessIdAndBarcodeAndIdNot(String businessId, String barcode, String id);
}
