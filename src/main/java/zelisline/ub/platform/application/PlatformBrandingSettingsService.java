package zelisline.ub.platform.application;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.api.dto.PlatformBrandingSettingsResponse;
import zelisline.ub.platform.domain.PlatformBrandingSettings;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.media.MediaStore;
import zelisline.ub.platform.repository.PlatformBrandingSettingsRepository;

/**
 * Platform branding managed in Super Admin. The email logo is uploaded to the
 * platform media account and shown in platform transactional mail (onboarding
 * sequence etc.). {@link #emailLogoUrl()} is read on the email send path and
 * returns {@code null} when nothing is configured, so the renderer falls back
 * to the built-in "K" wordmark badge.
 */
@Service
@RequiredArgsConstructor
public class PlatformBrandingSettingsService {

    private static final String LOGO_FOLDER = "ub/platform/branding/email-logo";

    private final PlatformBrandingSettingsRepository repository;
    private final MediaStore mediaStore;

    /** Read-only, null-safe accessor for the email send path. */
    @Transactional(readOnly = true)
    public String emailLogoUrl() {
        return repository.findById(PlatformBrandingSettings.SINGLETON_ID)
                .map(PlatformBrandingSettings::getEmailLogoUrl)
                .filter(u -> u != null && !u.isBlank())
                .orElse(null);
    }

    @Transactional
    public PlatformBrandingSettingsResponse getForSuperAdmin() {
        return toResponse(loadSingleton());
    }

    @Transactional
    public PlatformBrandingSettingsResponse uploadEmailLogo(byte[] fileBytes, String originalFilename) {
        if (!mediaStore.isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Media storage is not configured; cannot upload a logo.");
        }
        PlatformBrandingSettings row = loadSingleton();
        String previousPublicId = row.getEmailLogoPublicId();
        CloudinaryUploadResult uploaded =
                mediaStore.uploadImageToFolder(fileBytes, originalFilename, LOGO_FOLDER, false);
        row.setEmailLogoUrl(uploaded.secureUrl());
        row.setEmailLogoPublicId(uploaded.publicId());
        row.setUpdatedAt(Instant.now());
        PlatformBrandingSettingsResponse out = toResponse(repository.save(row));
        if (previousPublicId != null && !previousPublicId.equals(uploaded.publicId())) {
            mediaStore.destroyImage(previousPublicId);
        }
        return out;
    }

    @Transactional
    public PlatformBrandingSettingsResponse clearEmailLogo() {
        PlatformBrandingSettings row = loadSingleton();
        String previousPublicId = row.getEmailLogoPublicId();
        row.setEmailLogoUrl(null);
        row.setEmailLogoPublicId(null);
        row.setUpdatedAt(Instant.now());
        PlatformBrandingSettingsResponse out = toResponse(repository.save(row));
        if (previousPublicId != null && mediaStore.isConfigured()) {
            mediaStore.destroyImage(previousPublicId);
        }
        return out;
    }

    private PlatformBrandingSettingsResponse toResponse(PlatformBrandingSettings row) {
        return new PlatformBrandingSettingsResponse(row.getEmailLogoUrl(), row.getUpdatedAt());
    }

    private PlatformBrandingSettings loadSingleton() {
        return repository.findById(PlatformBrandingSettings.SINGLETON_ID)
                .orElseGet(this::createSingleton);
    }

    private PlatformBrandingSettings createSingleton() {
        PlatformBrandingSettings row = new PlatformBrandingSettings();
        row.setId(PlatformBrandingSettings.SINGLETON_ID);
        row.setUpdatedAt(Instant.now());
        return repository.save(row);
    }
}
