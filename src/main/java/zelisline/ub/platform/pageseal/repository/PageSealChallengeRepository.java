package zelisline.ub.platform.pageseal.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.platform.pageseal.domain.PageSealChallenge;

public interface PageSealChallengeRepository extends JpaRepository<PageSealChallenge, String> {

    Optional<PageSealChallenge> findFirstByScopeAndSubjectIdAndConsumedAtIsNullOrderByCreatedAtDesc(
            String scope, String subjectId);

    List<PageSealChallenge> findByScopeAndSubjectIdAndConsumedAtIsNull(String scope, String subjectId);

    Optional<PageSealChallenge> findFirstBySetupTokenHashAndConsumedAtIsNullOrderByCreatedAtDesc(
            String setupTokenHash);
}
