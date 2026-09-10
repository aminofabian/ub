package zelisline.ub.ai.application;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.api.dto.BrandingLogoGenerateRequest;
import zelisline.ub.ai.api.dto.BrandingLogoGenerateResponse;
import zelisline.ub.ai.application.provider.OpenAiImageClient;
import zelisline.ub.ai.application.provider.OpenRouterImageClient;
import zelisline.ub.ai.config.SokoMindProperties;
import zelisline.ub.ai.domain.AiRequestLog;
import zelisline.ub.ai.repository.AiRequestLogRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * One-shot shop logo from a merchant prompt. Returns PNG bytes; the client
 * previews and uploads through the existing branding logo path.
 */
@Service
@RequiredArgsConstructor
public class BrandingLogoAiService {

    private static final String SKILL = "branding_logo";

    private final SokoMindRuntimeService runtimeService;
    private final SokoMindProperties properties;
    private final OpenAiImageClient imageClient;
    private final OpenRouterImageClient openRouterImageClient;
    private final BusinessRepository businessRepository;
    private final AiRequestLogRepository requestLogRepository;

    public BrandingLogoGenerateResponse generate(
            String businessId,
            String userId,
            BrandingLogoGenerateRequest body
    ) {
        Business business = businessRepository
                .findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));

        ResolvedSokoMindConfig config = runtimeService.config();
        if (!config.enabled()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SokoMind is disabled. Enable it in Super Admin → Platform → SokoMind.");
        }
        if (!config.imageGenerationAvailable()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Logo generation needs an OpenAI or OpenRouter key. Set it in Super Admin → Platform → SokoMind.");
        }

        String prompt = body == null ? null : body.prompt();
        String shopName = firstNonBlank(body == null ? null : body.shopName(), business.getName());
        String shopType = body == null ? null : body.shopType();
        String primary = body == null ? null : body.primaryColor();
        String accent = body == null ? null : body.accentColor();

        if (!BrandingLogoPromptComposer.hasEnoughInput(prompt, shopName)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Describe the logo, or set a shop name first.");
        }

        String composed;
        try {
            composed = BrandingLogoPromptComposer.compose(prompt, shopName, shopType, primary, accent);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }

        String imageProvider = config.imageProvider();
        String requestId = UUID.randomUUID().toString();
        long started = System.currentTimeMillis();
        AiRequestLog log = new AiRequestLog();
        log.setId(requestId);
        log.setBusinessId(businessId);
        log.setUserId(userId);
        log.setSkill(SKILL);
        log.setSurface("onboarding");
        log.setRoutePath("onboarding/branding");
        log.setProvider(imageProvider.isBlank() ? "openai" : imageProvider);

        try {
            OpenAiImageClient.GeneratedImage image;
            if ("openrouter".equals(imageProvider)) {
                String model = firstNonBlank(
                        config.openrouterImageModel(),
                        properties.openrouter() == null ? null : properties.openrouter().imageModel(),
                        "google/gemini-2.5-flash-image");
                image = openRouterImageClient.generate(config, model, composed);
            } else {
                String model = properties.openai() == null ? "gpt-image-1" : properties.openai().imageModel();
                image = imageClient.generate(config, model, composed);
            }
            long latency = System.currentTimeMillis() - started;
            log.setSuccess(true);
            log.setModel(image.model());
            log.setPromptTokens(image.promptTokens());
            log.setCompletionTokens(image.completionTokens());
            log.setLatencyMs((int) Math.min(latency, Integer.MAX_VALUE));
            requestLogRepository.save(log);
            return new BrandingLogoGenerateResponse(requestId, image.mimeType(), image.base64());
        } catch (RuntimeException ex) {
            long latency = System.currentTimeMillis() - started;
            log.setSuccess(false);
            log.setLatencyMs((int) Math.min(latency, Integer.MAX_VALUE));
            log.setErrorMessage(truncate(ex.getMessage(), 500));
            requestLogRepository.save(log);
            throw ex;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b;
    }

    private static String firstNonBlank(String a, String b, String c) {
        String first = firstNonBlank(a, b);
        return firstNonBlank(first, c);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
