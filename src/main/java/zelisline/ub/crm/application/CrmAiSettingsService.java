package zelisline.ub.crm.application;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.domain.CrmAiSettings;
import zelisline.ub.crm.repository.CrmAiSettingsRepository;

/** Per-business AI/auto-reply settings. */
@Service
@RequiredArgsConstructor
public class CrmAiSettingsService {

    private final CrmAiSettingsRepository settingsRepository;

    @Transactional(readOnly = true)
    public CrmAiSettings get(String businessId) {
        return settingsRepository.findById(businessId).orElseGet(() -> defaults(businessId));
    }

    @Transactional
    public CrmAiSettings update(
            String businessId, boolean autoReplyEnabled, int maxRepliesPerConversation, String handoffUserId) {
        CrmAiSettings settings = settingsRepository.findById(businessId).orElseGet(() -> defaults(businessId));
        settings.setAutoReplyEnabled(autoReplyEnabled);
        settings.setMaxRepliesPerConversation(Math.max(0, Math.min(maxRepliesPerConversation, 20)));
        settings.setHandoffUserId(handoffUserId == null || handoffUserId.isBlank() ? null : handoffUserId.trim());
        settings.setUpdatedAt(Instant.now());
        return settingsRepository.save(settings);
    }

    private static CrmAiSettings defaults(String businessId) {
        CrmAiSettings settings = new CrmAiSettings();
        settings.setBusinessId(businessId);
        return settings;
    }
}
