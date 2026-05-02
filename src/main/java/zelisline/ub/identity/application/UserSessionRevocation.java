package zelisline.ub.identity.application;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.repository.UserSessionRepository;

/**
 * Commits session revocation independently so a follow-up {@code 401} in the same
 * request does not roll back reuse handling (PHASE_1_PLAN.md §3.3).
 */
@Service
@RequiredArgsConstructor
public class UserSessionRevocation {

    private final UserSessionRepository userSessionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllActiveForUserNow(String userId) {
        userSessionRepository.revokeAllActiveForUser(userId, Instant.now());
    }
}
