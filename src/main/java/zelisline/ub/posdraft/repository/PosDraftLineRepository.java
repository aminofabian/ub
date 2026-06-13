package zelisline.ub.posdraft.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.posdraft.domain.PosDraftLine;

public interface PosDraftLineRepository extends JpaRepository<PosDraftLine, String> {

    List<PosDraftLine> findByDraftIdOrderByLineIndexAsc(String draftId);

    Optional<PosDraftLine> findByIdAndDraftId(String id, String draftId);

    int countByDraftIdAndDeletedFalse(String draftId);
}
