package zelisline.ub.platform.application;

import java.time.Instant;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.application.ResolvedSokoMindConfig;
import zelisline.ub.ai.config.SokoMindProperties;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.platform.api.dto.SokoMindSettingsResponse;
import zelisline.ub.platform.api.dto.UpdateSokoMindSettingsRequest;
import zelisline.ub.platform.domain.PlatformSokoMindSettings;
import zelisline.ub.platform.repository.PlatformSokoMindSettingsRepository;

@Service
@RequiredArgsConstructor
public class PlatformSokoMindSettingsService {

    private static final Set<String> PROVIDERS = Set.of(
            PlatformSokoMindSettings.PROVIDER_OPENAI,
            PlatformSokoMindSettings.PROVIDER_ANTHROPIC,
            PlatformSokoMindSettings.PROVIDER_DEEPSEEK,
            PlatformSokoMindSettings.PROVIDER_RAPIDAPI_DEEPSEEK);

    private static final String DEFAULT_OPENAI_MINI = "gpt-4o-mini";
    private static final String DEFAULT_OPENAI_SMART = "gpt-4.1";
    private static final String DEFAULT_OPENAI_VISION = "gpt-4o";
    private static final String DEFAULT_ANTHROPIC_MINI = "claude-haiku-4-5-20251001";
    private static final String DEFAULT_ANTHROPIC_SMART = "claude-sonnet-4-5-20250929";
    private static final String DEFAULT_DEEPSEEK_MODEL = "DeepSeek-V3-0324";
    private static final String DEFAULT_DEEPSEEK_HOST = "deepseek-v31.p.rapidapi.com";
    private static final String DEFAULT_DEEPSEEK_URL = "https://deepseek-v31.p.rapidapi.com/";

    private final PlatformSokoMindSettingsRepository repository;
    private final CredentialEncryptionService encryptionService;
    private final SokoMindProperties envProperties;

    @Transactional(readOnly = true)
    public SokoMindSettingsResponse getForSuperAdmin() {
        PlatformSokoMindSettings row = loadSingleton();
        SecretRead secrets = readSecrets(row);
        return toResponse(row, secrets);
    }

    @Transactional
    public SokoMindSettingsResponse update(UpdateSokoMindSettingsRequest body) {
        PlatformSokoMindSettings row = loadSingleton();

        if (body.sokomindEnabled() != null) {
            row.setSokomindEnabled(body.sokomindEnabled());
        }
        if (body.guideEnabled() != null) {
            row.setGuideEnabled(body.guideEnabled());
        }
        if (body.brainEnabled() != null) {
            row.setBrainEnabled(body.brainEnabled());
        }
        if (body.eyeEnabled() != null) {
            row.setEyeEnabled(body.eyeEnabled());
        }
        if (body.primaryProvider() != null) {
            String provider = blankToNull(body.primaryProvider());
            if (provider != null) {
                provider = provider.toLowerCase().trim();
                if (!PROVIDERS.contains(provider)) {
                    provider = PlatformSokoMindSettings.PROVIDER_OPENAI;
                }
            } else {
                provider = PlatformSokoMindSettings.PROVIDER_OPENAI;
            }
            row.setPrimaryProvider(provider);
        }
        if (body.defaultLocale() != null) {
            String locale = blankToNull(body.defaultLocale());
            row.setDefaultLocale(locale != null ? locale : "en-KE");
        }

        if (body.openaiApiKey() != null) {
            row.setOpenaiApiKeyEnc(encryptOrClear(body.openaiApiKey()));
        }
        if (body.openaiBaseUrl() != null) {
            row.setOpenaiBaseUrl(blankToNull(body.openaiBaseUrl()));
        }
        if (body.openaiMiniModel() != null) {
            row.setOpenaiMiniModel(blankToNull(body.openaiMiniModel()));
        }
        if (body.openaiSmartModel() != null) {
            row.setOpenaiSmartModel(blankToNull(body.openaiSmartModel()));
        }
        if (body.openaiVisionModel() != null) {
            row.setOpenaiVisionModel(blankToNull(body.openaiVisionModel()));
        }

        if (body.anthropicApiKey() != null) {
            row.setAnthropicApiKeyEnc(encryptOrClear(body.anthropicApiKey()));
        }
        if (body.anthropicBaseUrl() != null) {
            row.setAnthropicBaseUrl(blankToNull(body.anthropicBaseUrl()));
        }
        if (body.anthropicMiniModel() != null) {
            row.setAnthropicMiniModel(blankToNull(body.anthropicMiniModel()));
        }
        if (body.anthropicSmartModel() != null) {
            row.setAnthropicSmartModel(blankToNull(body.anthropicSmartModel()));
        }

        if (body.deepseekApiKey() != null) {
            row.setDeepseekApiKeyEnc(encryptOrClear(body.deepseekApiKey()));
        }
        if (body.deepseekBaseUrl() != null) {
            row.setDeepseekBaseUrl(blankToNull(body.deepseekBaseUrl()));
        }
        if (body.deepseekHost() != null) {
            row.setDeepseekHost(blankToNull(body.deepseekHost()));
        }
        if (body.deepseekModel() != null) {
            row.setDeepseekModel(blankToNull(body.deepseekModel()));
        }

        if (body.industryCompareEnabled() != null) {
            row.setIndustryCompareEnabled(body.industryCompareEnabled());
        }
        if (body.industryCompareMinTwins() != null) {
            int min = Math.max(2, Math.min(100, body.industryCompareMinTwins()));
            row.setIndustryCompareMinTwins(min);
        }
        if (Boolean.TRUE.equals(body.clearDailyTokenBudget())) {
            row.setDailyTokenBudgetPerTenant(null);
        } else if (body.dailyTokenBudgetPerTenant() != null) {
            int budget = body.dailyTokenBudgetPerTenant();
            row.setDailyTokenBudgetPerTenant(budget <= 0 ? null : budget);
        }
        if (body.maxToolCallsPerRequest() != null) {
            int max = Math.max(1, Math.min(32, body.maxToolCallsPerRequest()));
            row.setMaxToolCallsPerRequest(max);
        }
        if (body.systemPromptExtra() != null) {
            row.setSystemPromptExtra(blankToNull(body.systemPromptExtra()));
        }

        row.setUpdatedAt(Instant.now());
        PlatformSokoMindSettings saved = repository.save(row);
        return toResponse(saved, readSecrets(saved));
    }

    /** Runtime resolve for gateway / skills — DB preferred over env. */
    @Transactional(readOnly = true)
    public ResolvedSokoMindConfig resolve() {
        PlatformSokoMindSettings row = loadSingleton();
        SecretRead secrets = readSecrets(row);
        var env = envProperties;
        var openaiEnv = env.openai();
        var anthropicEnv = env.anthropic();
        var deepseekEnv = env.deepseek();

        // Master switch: Super Admin DB OR emergency env force-enable.
        boolean enabled = row.isSokomindEnabled() || env.enabled();

        String openaiKey =
                secrets.readable
                        ? firstNonBlank(secrets.openaiApiKey, openaiEnv.apiKey())
                        : blankToNull(openaiEnv.apiKey());
        String anthropicKey =
                secrets.readable
                        ? firstNonBlank(secrets.anthropicApiKey, anthropicEnv.apiKey())
                        : blankToNull(anthropicEnv.apiKey());
        String deepseekKey =
                secrets.readable
                        ? firstNonBlank(secrets.deepseekApiKey, deepseekEnv.apiKey())
                        : blankToNull(deepseekEnv.apiKey());

        String provider =
                firstNonBlank(
                        trimToNull(row.getPrimaryProvider()),
                        blankToNull(env.primaryProvider()),
                        PlatformSokoMindSettings.PROVIDER_OPENAI);

        return new ResolvedSokoMindConfig(
                enabled,
                row.isGuideEnabled(),
                row.isBrainEnabled(),
                row.isEyeEnabled(),
                provider,
                firstNonBlank(trimToNull(row.getDefaultLocale()), env.defaultLocale(), "en-KE"),
                openaiKey,
                firstNonBlank(trimToNull(row.getOpenaiBaseUrl()), openaiEnv.baseUrl()),
                firstNonBlank(
                        trimToNull(row.getOpenaiMiniModel()),
                        openaiEnv.miniModel(),
                        DEFAULT_OPENAI_MINI),
                firstNonBlank(
                        trimToNull(row.getOpenaiSmartModel()),
                        openaiEnv.smartModel(),
                        DEFAULT_OPENAI_SMART),
                firstNonBlank(
                        trimToNull(row.getOpenaiVisionModel()),
                        openaiEnv.visionModel(),
                        DEFAULT_OPENAI_VISION),
                anthropicKey,
                firstNonBlank(trimToNull(row.getAnthropicBaseUrl()), anthropicEnv.baseUrl()),
                firstNonBlank(
                        trimToNull(row.getAnthropicMiniModel()),
                        anthropicEnv.miniModel(),
                        DEFAULT_ANTHROPIC_MINI),
                firstNonBlank(
                        trimToNull(row.getAnthropicSmartModel()),
                        anthropicEnv.smartModel(),
                        DEFAULT_ANTHROPIC_SMART),
                deepseekKey,
                firstNonBlank(
                        trimToNull(row.getDeepseekBaseUrl()),
                        deepseekEnv.baseUrl(),
                        DEFAULT_DEEPSEEK_URL),
                firstNonBlank(
                        trimToNull(row.getDeepseekHost()), deepseekEnv.host(), DEFAULT_DEEPSEEK_HOST),
                firstNonBlank(
                        trimToNull(row.getDeepseekModel()),
                        deepseekEnv.model(),
                        DEFAULT_DEEPSEEK_MODEL),
                row.isIndustryCompareEnabled(),
                row.getIndustryCompareMinTwins() > 0
                        ? row.getIndustryCompareMinTwins()
                        : Math.max(2, env.industryCompareMinTwins()),
                row.getDailyTokenBudgetPerTenant() != null
                        ? row.getDailyTokenBudgetPerTenant()
                        : env.dailyTokenBudgetPerTenant(),
                row.getMaxToolCallsPerRequest() > 0
                        ? row.getMaxToolCallsPerRequest()
                        : Math.max(1, env.maxToolCallsPerRequest()),
                trimToNull(row.getSystemPromptExtra()));
    }

    private SokoMindSettingsResponse toResponse(PlatformSokoMindSettings row, SecretRead secrets) {
        var env = envProperties;
        var openaiEnv = env.openai();
        var anthropicEnv = env.anthropic();
        var deepseekEnv = env.deepseek();

        return new SokoMindSettingsResponse(
                row.isSokomindEnabled(),
                row.isGuideEnabled(),
                row.isBrainEnabled(),
                row.isEyeEnabled(),
                firstNonBlank(
                        trimToNull(row.getPrimaryProvider()),
                        blankToNull(env.primaryProvider()),
                        PlatformSokoMindSettings.PROVIDER_OPENAI),
                firstNonBlank(trimToNull(row.getDefaultLocale()), env.defaultLocale(), "en-KE"),
                secrets.hasOpenaiApiKey,
                firstNonBlank(trimToNull(row.getOpenaiBaseUrl()), openaiEnv.baseUrl(), ""),
                firstNonBlank(
                        trimToNull(row.getOpenaiMiniModel()),
                        openaiEnv.miniModel(),
                        DEFAULT_OPENAI_MINI),
                firstNonBlank(
                        trimToNull(row.getOpenaiSmartModel()),
                        openaiEnv.smartModel(),
                        DEFAULT_OPENAI_SMART),
                firstNonBlank(
                        trimToNull(row.getOpenaiVisionModel()),
                        openaiEnv.visionModel(),
                        DEFAULT_OPENAI_VISION),
                secrets.hasAnthropicApiKey,
                firstNonBlank(trimToNull(row.getAnthropicBaseUrl()), anthropicEnv.baseUrl(), ""),
                firstNonBlank(
                        trimToNull(row.getAnthropicMiniModel()),
                        anthropicEnv.miniModel(),
                        DEFAULT_ANTHROPIC_MINI),
                firstNonBlank(
                        trimToNull(row.getAnthropicSmartModel()),
                        anthropicEnv.smartModel(),
                        DEFAULT_ANTHROPIC_SMART),
                secrets.hasDeepseekApiKey,
                firstNonBlank(
                        trimToNull(row.getDeepseekBaseUrl()),
                        deepseekEnv.baseUrl(),
                        DEFAULT_DEEPSEEK_URL),
                firstNonBlank(
                        trimToNull(row.getDeepseekHost()), deepseekEnv.host(), DEFAULT_DEEPSEEK_HOST),
                firstNonBlank(
                        trimToNull(row.getDeepseekModel()),
                        deepseekEnv.model(),
                        DEFAULT_DEEPSEEK_MODEL),
                row.isIndustryCompareEnabled(),
                row.getIndustryCompareMinTwins(),
                row.getDailyTokenBudgetPerTenant(),
                row.getMaxToolCallsPerRequest(),
                row.getSystemPromptExtra(),
                openaiEnv.configured(),
                anthropicEnv.configured(),
                deepseekEnv.configured(),
                secrets.readable,
                secrets.errorMessage,
                encryptionService.usesEphemeralKey(),
                row.getUpdatedAt());
    }

    private PlatformSokoMindSettings loadSingleton() {
        return repository
                .findById(PlatformSokoMindSettings.SINGLETON_ID)
                .orElseGet(this::createSingleton);
    }

    private PlatformSokoMindSettings createSingleton() {
        PlatformSokoMindSettings row = new PlatformSokoMindSettings();
        row.setId(PlatformSokoMindSettings.SINGLETON_ID);
        row.setUpdatedAt(Instant.now());
        return repository.save(row);
    }

    private SecretRead readSecrets(PlatformSokoMindSettings row) {
        String persistenceHint = null;
        if (encryptionService.usesEphemeralKey()) {
            persistenceHint =
                    "APP_PAYMENTS_ENCRYPTION_KEY is not set; stored secrets work until the next "
                            + "restart, then must be re-saved. Set the key in production.";
        }
        try {
            return new SecretRead(
                    true,
                    hasEncrypted(row.getOpenaiApiKeyEnc()),
                    hasEncrypted(row.getAnthropicApiKeyEnc()),
                    hasEncrypted(row.getDeepseekApiKeyEnc()),
                    decryptOrNull(row.getOpenaiApiKeyEnc()),
                    decryptOrNull(row.getAnthropicApiKeyEnc()),
                    decryptOrNull(row.getDeepseekApiKeyEnc()),
                    persistenceHint);
        } catch (RuntimeException ex) {
            return new SecretRead(
                    false,
                    hasEncrypted(row.getOpenaiApiKeyEnc()),
                    hasEncrypted(row.getAnthropicApiKeyEnc()),
                    hasEncrypted(row.getDeepseekApiKeyEnc()),
                    null,
                    null,
                    null,
                    firstNonBlank(ex.getMessage(), persistenceHint));
        }
    }

    private String encryptOrClear(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return encryptionService.encryptSecret(trimmed);
    }

    private String decryptOrNull(String enc) {
        if (enc == null || enc.isBlank()) {
            return null;
        }
        try {
            return encryptionService.decrypt(enc);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean hasEncrypted(String enc) {
        return enc != null && !enc.isBlank();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trimToNull(String value) {
        return blankToNull(value);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private record SecretRead(
            boolean readable,
            boolean hasOpenaiApiKey,
            boolean hasAnthropicApiKey,
            boolean hasDeepseekApiKey,
            String openaiApiKey,
            String anthropicApiKey,
            String deepseekApiKey,
            String errorMessage
    ) {}
}
