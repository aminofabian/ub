package zelisline.ub.identity.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.ApiKeyResponse;
import zelisline.ub.identity.api.dto.CreatedApiKeyResponse;
import zelisline.ub.identity.api.dto.CreateApiKeyRequest;
import zelisline.ub.identity.domain.ApiKey;
import zelisline.ub.identity.repository.ApiKeyRepository;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String PREFIX = "kpos_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;

    @Transactional(readOnly = true)
    public Page<ApiKeyResponse> list(HttpServletRequest http, TenantPrincipal principal, Pageable pageable) {
        String businessId = TenantRequestIds.requireMatchingTenant(http, principal.businessId());
        return apiKeyRepository.findByBusinessIdOrderByCreatedAtDesc(businessId, pageable)
                .map(this::toResponse);
    }

    @Transactional
    public CreatedApiKeyResponse create(HttpServletRequest http, TenantPrincipal principal, CreateApiKeyRequest request) {
        String businessId = TenantRequestIds.requireMatchingTenant(http, principal.businessId());
        String tokenPrefix = randomAlphanumeric(8);
        byte[] secretBytes = new byte[32];
        RANDOM.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        String fullToken = PREFIX + tokenPrefix + "_" + secret;
        String hash = TokenHasher.sha256Hex(fullToken);

        ApiKey entity = new ApiKey();
        entity.setBusinessId(businessId);
        entity.setUserId(principal.userId());
        entity.setLabel(request.label().trim());
        entity.setTokenHash(hash);
        entity.setTokenPrefix(tokenPrefix);
        entity.setScopes(new ArrayList<>(request.scopes()));
        entity.setActive(true);
        apiKeyRepository.save(entity);

        return new CreatedApiKeyResponse(
                entity.getId(),
                fullToken,
                tokenPrefix,
                entity.getLabel(),
                copyScopeList(entity.getScopes()),
                entity.getCreatedAt()
        );
    }

    @Transactional
    public void revoke(HttpServletRequest http, TenantPrincipal principal, String apiKeyId) {
        String businessId = TenantRequestIds.requireMatchingTenant(http, principal.businessId());
        ApiKey key = apiKeyRepository.findByIdAndBusinessId(apiKeyId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API key not found"));
        key.setActive(false);
        apiKeyRepository.save(key);
    }

    private ApiKeyResponse toResponse(ApiKey entity) {
        return new ApiKeyResponse(
                entity.getId(),
                entity.getLabel(),
                entity.getTokenPrefix(),
                copyScopeList(entity.getScopes()),
                entity.isActive(),
                entity.getLastUsedAt(),
                entity.getExpiresAt(),
                entity.getCreatedAt()
        );
    }

    private static List<String> copyScopeList(List<String> scopes) {
        return scopes == null || scopes.isEmpty() ? List.of() : List.copyOf(scopes);
    }

    private static String randomAlphanumeric(int len) {
        final String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
