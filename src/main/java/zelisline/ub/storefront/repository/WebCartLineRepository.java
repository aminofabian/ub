package zelisline.ub.storefront.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.storefront.domain.WebCartLine;

public interface WebCartLineRepository extends JpaRepository<WebCartLine, String> {

    List<WebCartLine> findByCartIdOrderByCreatedAtAsc(String cartId);

    Optional<WebCartLine> findByCartIdAndItemId(String cartId, String itemId);

    int countByCartId(String cartId);

    void deleteByCartIdAndItemId(String cartId, String itemId);
}
