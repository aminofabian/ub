package zelisline.ub.purchasing.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.purchasing.domain.GoodsReceiptLine;

public interface GoodsReceiptLineRepository extends JpaRepository<GoodsReceiptLine, String> {

    List<GoodsReceiptLine> findByGoodsReceiptIdOrderByIdAsc(String goodsReceiptId);
}
