package zelisline.ub.platform.application;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.api.dto.PlatformAuthSettingsResponse;
import zelisline.ub.platform.api.dto.UpdatePlatformAuthSettingsRequest;
import zelisline.ub.platform.domain.PlatformAuthSettings;
import zelisline.ub.platform.repository.PlatformAuthSettingsRepository;

@Service
@RequiredArgsConstructor
public class PlatformAuthSettingsService {

    private final PlatformAuthSettingsRepository repository;

    @Value("${app.auth.email-verification-required:true}")
    private boolean envEmailVerificationRequired;

    @Transactional
    public PlatformAuthSettingsResponse getForSuperAdmin() {
        PlatformAuthSettings row = loadSingleton();
        return toResponse(row);
    }

    @Transactional
    public PlatformAuthSettingsResponse update(UpdatePlatformAuthSettingsRequest body) {
        PlatformAuthSettings row = loadSingleton();
        if (body.emailVerificationRequired() != null) {
            row.setEmailVerificationRequired(body.emailVerificationRequired());
        }
        row.setUpdatedAt(Instant.now());
        return toResponse(repository.save(row));
    }

    /**
     * Super-admin row wins once it exists. First boot (and create-drop tests)
     * seed from {@code app.auth.email-verification-required}.
     */
    @Transactional
    public boolean isEmailVerificationRequired() {
        return loadSingleton().isEmailVerificationRequired();
    }

    private PlatformAuthSettingsResponse toResponse(PlatformAuthSettings row) {
        return new PlatformAuthSettingsResponse(
                row.isEmailVerificationRequired(),
                row.getUpdatedAt());
    }

    private PlatformAuthSettings loadSingleton() {
        return repository
                .findById(PlatformAuthSettings.SINGLETON_ID)
                .orElseGet(this::createSingleton);
    }

    private PlatformAuthSettings createSingleton() {
        PlatformAuthSettings row = new PlatformAuthSettings();
        row.setId(PlatformAuthSettings.SINGLETON_ID);
        row.setEmailVerificationRequired(envEmailVerificationRequired);
        row.setUpdatedAt(Instant.now());
        return repository.save(row);
    }
}
