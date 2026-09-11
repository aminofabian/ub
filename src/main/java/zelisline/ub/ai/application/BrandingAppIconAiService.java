package zelisline.ub.ai.application;

import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.api.dto.BrandingAppIconGenerateRequest;
import zelisline.ub.ai.api.dto.BrandingAppIconGenerateResponse;
import zelisline.ub.ai.application.provider.OpenAiImageClient;
import zelisline.ub.ai.application.provider.OpenRouterImageClient;
import zelisline.ub.ai.config.SokoMindProperties;
import zelisline.ub.ai.domain.AiRequestLog;
import zelisline.ub.ai.repository.AiRequestLogRepository;
import zelisline.ub.tenancy.application.StorefrontSettingsService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Turns the shop's saved logo into a matching home-screen / PWA icon.
 */
@Service
@RequiredArgsConstructor
public class BrandingAppIconAiService {

    private static final Logger log = LoggerFactory.getLogger(BrandingAppIconAiService.class);
    private static final String SKILL = "branding_app_icon";
    private static final int CONNECT_MS = 10_000;
    private static final int SOCKET_MS = 20_000;
    private static final int MAX_LOGO_BYTES = 1_500_000;

    private final SokoMindRuntimeService runtimeService;
    private final SokoMindProperties properties;
    private final OpenAiImageClient imageClient;
    private final OpenRouterImageClient openRouterImageClient;
    private final BusinessRepository businessRepository;
    private final StorefrontSettingsService storefrontSettingsService;
    private final AiRequestLogRepository requestLogRepository;

    public BrandingAppIconGenerateResponse generate(
            String businessId,
            String userId,
            BrandingAppIconGenerateRequest body
    ) {
        Business business = businessRepository
                .findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));

        String logoUrl = storefrontSettingsService.readBrandingLogoUrl(business.getSettings());
        if (logoUrl == null || logoUrl.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Add a shop logo first, then generate a matching home-screen icon.");
        }
        LogoBytes logo = downloadLogo(logoUrl);

        ResolvedSokoMindConfig config = runtimeService.config();
        if (!config.enabled()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SokoMind is disabled. Enable it in Super Admin → Platform → SokoMind.");
        }
        if (!config.imageGenerationAvailable()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Icon generation needs an OpenAI or OpenRouter key. Set it in Super Admin → Platform → SokoMind.");
        }

        String prompt = body == null ? null : body.prompt();
        String shopName = firstNonBlank(body == null ? null : body.shopName(), business.getName());
        String shopType = body == null ? null : body.shopType();
        String primary = body == null ? null : body.primaryColor();
        String accent = body == null ? null : body.accentColor();
        String composed = BrandingLogoPromptComposer.compose(
                prompt, shopName, shopType, primary, accent, BrandingLogoPromptComposer.Theme.APP_ICON);

        String imageProvider = config.imageProvider();
        String requestId = UUID.randomUUID().toString();
        long started = System.currentTimeMillis();
        AiRequestLog logRow = new AiRequestLog();
        logRow.setId(requestId);
        logRow.setBusinessId(businessId);
        logRow.setUserId(userId);
        logRow.setSkill(SKILL);
        logRow.setSurface("dashboard");
        logRow.setRoutePath("business/branding");
        logRow.setProvider(imageProvider.isBlank() ? "openai" : imageProvider);

        try {
            OpenAiImageClient.GeneratedImage image = generateIcon(config, imageProvider, composed, logo);
            long latency = System.currentTimeMillis() - started;
            logRow.setSuccess(true);
            logRow.setModel(image.model());
            logRow.setPromptTokens(image.promptTokens());
            logRow.setCompletionTokens(image.completionTokens());
            logRow.setLatencyMs((int) Math.min(latency, Integer.MAX_VALUE));
            requestLogRepository.save(logRow);
            return new BrandingAppIconGenerateResponse(requestId, image.mimeType(), image.base64());
        } catch (RuntimeException ex) {
            long latency = System.currentTimeMillis() - started;
            logRow.setSuccess(false);
            logRow.setLatencyMs((int) Math.min(latency, Integer.MAX_VALUE));
            logRow.setErrorMessage(truncate(ex.getMessage(), 500));
            requestLogRepository.save(logRow);
            throw ex;
        }
    }

    private OpenAiImageClient.GeneratedImage generateIcon(
            ResolvedSokoMindConfig config,
            String imageProvider,
            String composed,
            LogoBytes logo
    ) {
        if ("openrouter".equals(imageProvider)) {
            String model = firstNonBlank(
                    config.openrouterImageModel(),
                    properties.openrouter() == null ? null : properties.openrouter().imageModel(),
                    "google/gemini-2.5-flash-image");
            String dataUrl = "data:" + logo.mimeType() + ";base64," + Base64.getEncoder().encodeToString(logo.bytes());
            return openRouterImageClient.generateOpaqueWithReference(config, model, composed, dataUrl);
        }
        String model = properties.openai() == null ? "gpt-image-1" : properties.openai().imageModel();
        try {
            return imageClient.edit(config, model, composed, logo.bytes(), logo.filename());
        } catch (ResponseStatusException ex) {
            log.warn("OpenAI logo edit failed, falling back to generate: {}", ex.getReason());
            return imageClient.generateOpaque(config, model, composed);
        }
    }

    private static LogoBytes downloadLogo(String url) {
        String trimmed = url.strip();
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The shop logo URL cannot be fetched.");
        }
        try {
            HttpResponse<byte[]> response = Unirest.get(trimmed)
                    .connectTimeout(CONNECT_MS)
                    .socketTimeout(SOCKET_MS)
                    .asBytes();
            if (response.getStatus() < 200 || response.getStatus() >= 300 || response.getBody() == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Could not download the shop logo. Try uploading it again.");
            }
            byte[] bytes = response.getBody();
            if (bytes.length < 32 || bytes.length > MAX_LOGO_BYTES) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "The shop logo is too large or unreadable to turn into an app icon.");
            }
            String contentType = response.getHeaders().getFirst("Content-Type");
            String mime = mimeFrom(contentType, trimmed);
            return new LogoBytes(bytes, mime, filenameFrom(trimmed, mime));
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Could not download the shop logo. Try uploading it again.");
        }
    }

    private static String mimeFrom(String contentType, String url) {
        String type = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (type.startsWith("image/") && !type.contains("svg")) {
            return type;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".jpg") || lower.contains(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.contains(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private static String filenameFrom(String url, String mime) {
        String ext = switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "png";
        };
        int slash = url.lastIndexOf('/');
        String last = slash >= 0 ? url.substring(slash + 1) : "logo";
        int query = last.indexOf('?');
        if (query >= 0) {
            last = last.substring(0, query);
        }
        if (last.isBlank() || last.length() > 80) {
            return "logo." + ext;
        }
        if (!last.contains(".")) {
            return last + "." + ext;
        }
        return last;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b;
    }

    private static String firstNonBlank(String a, String b, String c) {
        return firstNonBlank(firstNonBlank(a, b), c);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record LogoBytes(byte[] bytes, String mimeType, String filename) {}
}
