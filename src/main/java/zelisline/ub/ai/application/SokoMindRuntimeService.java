package zelisline.ub.ai.application;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import zelisline.ub.platform.application.PlatformSokoMindSettingsService;

/** Facade for future Guide / Brain / Eye skills — always resolve via Super Admin + env. */
@Service
@RequiredArgsConstructor
public class SokoMindRuntimeService {

    private final PlatformSokoMindSettingsService settingsService;

    public ResolvedSokoMindConfig config() {
        return settingsService.resolve();
    }

    public boolean isEnabled() {
        return config().enabled();
    }

    public boolean isGuideEnabled() {
        ResolvedSokoMindConfig cfg = config();
        return cfg.enabled() && cfg.guideEnabled();
    }

    public boolean isBrainEnabled() {
        ResolvedSokoMindConfig cfg = config();
        return cfg.enabled() && cfg.brainEnabled();
    }

    public boolean isEyeEnabled() {
        ResolvedSokoMindConfig cfg = config();
        return cfg.enabled() && cfg.eyeEnabled();
    }
}
