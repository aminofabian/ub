package zelisline.ub.ai.application;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.api.dto.BrandingLogoGenerateRequest;
import zelisline.ub.ai.api.dto.BrandingLogoGenerateResponse;
import zelisline.ub.ai.api.dto.BrandingLogoVariantDto;
import zelisline.ub.ai.application.provider.OpenAiImageClient;
import zelisline.ub.ai.application.provider.OpenRouterImageClient;
import zelisline.ub.ai.config.SokoMindProperties;
import zelisline.ub.ai.domain.AiRequestLog;
import zelisline.ub.ai.repository.AiRequestLogRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Two shop marks from one merchant prompt: light chrome and dark chrome.
 * Returns PNG bytes; the client previews and uploads through branding.
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

        String lightPrompt = BrandingLogoPromptComposer.compose(
                prompt, shopName, shopType, primary, accent, BrandingLogoPromptComposer.Theme.LIGHT);
        String darkPrompt = BrandingLogoPromptComposer.compose(
                prompt, shopName, shopType, primary, accent, BrandingLogoPromptComposer.Theme.DARK);

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
            CompletableFuture<OpenAiImageClient.GeneratedImage> lightFuture =
                    CompletableFuture.supplyAsync(() -> generateOne(config, imageProvider, lightPrompt));
            CompletableFuture<OpenAiImageClient.GeneratedImage> darkFuture =
                    CompletableFuture.supplyAsync(() -> generateOne(config, imageProvider, darkPrompt));
            OpenAiImageClient.GeneratedImage light = unwrap(lightFuture);
            OpenAiImageClient.GeneratedImage dark = unwrap(darkFuture);
            long latency = System.currentTimeMillis() - started;
            log.setSuccess(true);
            log.setModel(firstNonBlank(light.model(), dark.model()));
            log.setPromptTokens(sumTokens(light.promptTokens(), dark.promptTokens()));
            log.setCompletionTokens(sumTokens(light.completionTokens(), dark.completionTokens()));
            log.setLatencyMs((int) Math.min(latency, Integer.MAX_VALUE));
            requestLogRepository.save(log);
            return new BrandingLogoGenerateResponse(
                    requestId,
                    List.of(
                            new BrandingLogoVariantDto("light", light.mimeType(), light.base64()),
                            new BrandingLogoVariantDto("dark", dark.mimeType(), dark.base64())));
        } catch (RuntimeException ex) {
            long latency = System.currentTimeMillis() - started;
            log.setSuccess(false);
            log.setLatencyMs((int) Math.min(latency, Integer.MAX_VALUE));
            log.setErrorMessage(truncate(ex.getMessage(), 500));
            requestLogRepository.save(log);
            throw ex;
        }
    }

    private OpenAiImageClient.GeneratedImage generateOne(
            ResolvedSokoMindConfig config,
            String imageProvider,
            String composed
    ) {
        if ("openrouter".equals(imageProvider)) {
            String model = firstNonBlank(
                    config.openrouterImageModel(),
                    properties.openrouter() == null ? null : properties.openrouter().imageModel(),
                    "google/gemini-2.5-flash-image");
            return openRouterImageClient.generate(config, model, composed);
        }
        String model = properties.openai() == null ? "gpt-image-1" : properties.openai().imageModel();
        return imageClient.generate(config, model, composed);
    }

    private static OpenAiImageClient.GeneratedImage unwrap(
            CompletableFuture<OpenAiImageClient.GeneratedImage> future
    ) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    cause.getMessage() == null ? "Logo generation failed." : cause.getMessage(),
                    cause);
        }
    }

    private static Integer sumTokens(Integer a, Integer b) {
        if (a == null && b == null) {
            return null;
        }
        return Integer.valueOf((a == null ? 0 : a) + (b == null ? 0 : b));
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
