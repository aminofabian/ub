package zelisline.ub.grocery.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.grocery.domain.GroceryDraftLine;

public interface GroceryDraftLineRepository extends JpaRepository<GroceryDraftLine, String> {

    List<GroceryDraftLine> findByDraftIdOrderByLineIndexAsc(String draftId);

    Optional<GroceryDraftLine> findByIdAndDraftId(String id, String draftId);

    int countByDraftIdAndDeletedFalse(String draftId);
}
