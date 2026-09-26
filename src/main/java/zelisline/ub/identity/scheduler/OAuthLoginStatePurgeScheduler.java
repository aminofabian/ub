package zelisline.ub.identity.scheduler;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.identity.repository.OAuthLoginStateRepository;

/**
 * Drops expired / abandoned Google OAuth login states (PKCE verifiers + nonces).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OAuthLoginStatePurgeScheduler {

    private final OAuthLoginStateRepository oauthLoginStateRepository;

    @Scheduled(cron = "${app.auth.oauth-state-purge-cron:0 20 * * * *}")
    @Transactional
    public void purgeExpired() {
        int removed = oauthLoginStateRepository.deleteByExpiresAtBefore(Instant.now());
        if (removed > 0) {
            log.info("Purged {} expired oauth_login_states", removed);
        }
    }
}
