package zelisline.ub.sales.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.sales.domain.SaleItem;

public interface SaleItemRepository extends JpaRepository<SaleItem, String> {

    List<SaleItem> findBySaleIdOrderByLineIndexAsc(String saleId);

    java.util.Optional<SaleItem> findByIdAndSaleId(String id, String saleId);
}
