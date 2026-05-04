package zelisline.ub.identity.application;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.ApiKey;
import zelisline.ub.identity.repository.ApiKeyRepository;
import zelisline.ub.platform.security.ApiKeyPrincipal;

/** Loads API keys for {@link zelisline.ub.platform.security.ApiKeyAuthenticationFilter}. */
@Service
@RequiredArgsConstructor
public class ApiKeyAuthService {

    private final ApiKeyRepository apiKeyRepository;

    @Transactional(readOnly = true)
    public Optional<ApiKeyPrincipal> authenticateRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String trimmed = rawToken.trim();
        String hash = TokenHasher.sha256Hex(trimmed);
        return apiKeyRepository.findByTokenHash(hash)
                .filter(ApiKey::isActive)
                .filter(k -> k.getExpiresAt() == null || !Instant.now().isAfter(k.getExpiresAt()))
                .map(k -> new ApiKeyPrincipal(k.getId(), k.getBusinessId(), scopeSet(k.getScopes())));
    }

    @Transactional
    public void touchLastUsed(String apiKeyId) {
        apiKeyRepository.findById(apiKeyId).ifPresent(k -> {
            k.setLastUsedAt(Instant.now());
            apiKeyRepository.save(k);
        });
    }

    private static Set<String> scopeSet(List<String> scopes) {
        return new HashSet<>(scopes == null || scopes.isEmpty() ? List.of() : scopes);
    }
}
