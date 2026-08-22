package zelisline.ub.ai.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.api.dto.StorefrontDesignSuggestRequest;
import zelisline.ub.ai.api.dto.StorefrontDesignSuggestResponse;
import zelisline.ub.ai.application.provider.AiChatCompletionRequest;
import zelisline.ub.ai.application.provider.AiChatCompletionResult;
import zelisline.ub.ai.application.provider.AiProviderRouter;
import zelisline.ub.ai.domain.AiRequestLog;
import zelisline.ub.ai.repository.AiRequestLogRepository;
import zelisline.ub.tenancy.api.dto.OnboardingSettingsResponse;
import zelisline.ub.tenancy.api.dto.StorefrontSettingsResponse;
import zelisline.ub.tenancy.api.dto.TenantConfigBundle;
import zelisline.ub.tenancy.application.BusinessOnboardingSettingsService;
import zelisline.ub.tenancy.application.StorefrontSettingsService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * "Make my store look better" — one-shot AI redesign of the storefront.
 *
 * Follows the SokoMind pattern: gate on platform config, call the configured
 * provider (DeepSeek etc.) with a strict JSON schema, validate every field the
 * model returns, and log the call for cost/abuse visibility. The merchant never
 * sees a provider key — keys live in Super Admin → Platform → SokoMind.
 */
@Service
@RequiredArgsConstructor
public class StorefrontDesignAiService {

    private static final String SKILL = "storefront_design";
    private static final int MAX_OUTPUT_TOKENS = 1400;

    private static final Pattern FENCE = Pattern.compile("(?s)^\\s*```(?:json)?\\s*(.*?)\\s*```\\s*$");
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final List<String> RADIUS_VALUES = List.of("sharp", "soft", "round");
    private static final List<String> BUTTONS_VALUES = List.of("solid", "outline", "pill");
    private static final List<String> DENSITY_VALUES = List.of("compact", "cozy", "airy");

    private final SokoMindRuntimeService runtimeService;
    private final AiProviderRouter providerRouter;
    private final BusinessRepository businessRepository;
    private final StorefrontSettingsService storefrontSettingsService;
    private final BusinessOnboardingSettingsService onboardingSettingsService;
    private final AiRequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public StorefrontDesignSuggestResponse suggest(
            String businessId,
            String userId,
            StorefrontDesignSuggestRequest body
    ) {
        Business business = businessRepository
                .findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));

        String requestId = UUID.randomUUID().toString();
        long started = System.currentTimeMillis();
        AiRequestLog log = new AiRequestLog();
        log.setId(requestId);
        log.setBusinessId(businessId);
        log.setUserId(userId);
        log.setSkill(SKILL);
        log.setSurface("storefront");
        log.setRoutePath("business/design");

        try {
            String context = buildContextJson(business, body.designJson());
            AiChatCompletionResult result = providerRouter.completeSmart(
                    new AiChatCompletionRequest(
                            null,
                            List.of(
                                    new AiChatCompletionRequest.AiChatMessage("system", SYSTEM_PROMPT),
                                    new AiChatCompletionRequest.AiChatMessage(
                                            "user",
                                            "Merchant request: " + body.prompt().strip()
                                                    + "\n\nCurrent store (JSON):\n" + context)),
                            0.4,
                            MAX_OUTPUT_TOKENS));

            long latency = System.currentTimeMillis() - started;
            log.setSuccess(true);
            log.setProvider(result.provider());
            log.setModel(result.model());
            log.setPromptTokens(result.promptTokens());
            log.setCompletionTokens(result.completionTokens());
            log.setLatencyMs((int) Math.min(latency, Integer.MAX_VALUE));
            requestLogRepository.save(log);

            StorefrontDesignSuggestResponse parsed = parseSuggestions(result.content(), requestId);
            return parsed;
        } catch (RuntimeException ex) {
            long latency = System.currentTimeMillis() - started;
            log.setSuccess(false);
            log.setLatencyMs((int) Math.min(latency, Integer.MAX_VALUE));
            log.setErrorMessage(truncate(ex.getMessage(), 500));
            requestLogRepository.save(log);
            throw ex;
        }
    }

    private String buildContextJson(Business business, String designJsonOverride) {
        try {
            StorefrontSettingsResponse sf = storefrontSettingsService.readFromSettingsJson(business.getSettings());
            TenantConfigBundle config = storefrontSettingsService.readTenantConfig(
                    business.getSettings(), business.getName());
            OnboardingSettingsResponse onboarding = onboardingSettingsService
                    .readFromSettingsJson(business.getSettings());

            String design = firstNonBlank(designJsonOverride, sf.designJson());
            JsonNode designNode = design == null ? null : parseLenient(design);

            ObjectNode businessNode = objectMapper.createObjectNode()
                    .put("name", business.getName())
                    .put("slug", business.getSlug())
                    .put("currency", business.getCurrency())
                    .put("countryCode", business.getCountryCode());

            ObjectNode brandingNode = objectMapper.createObjectNode()
                    .put("displayName", nullable(config.branding().displayName()))
                    .put("primaryColor", nullable(config.branding().primaryColor()))
                    .put("accentColor", nullable(config.branding().accentColor()))
                    .put("hasLogo", config.branding().logoUrl() != null && !config.branding().logoUrl().isBlank());

            ObjectNode onboardingNode = objectMapper.createObjectNode()
                    .put("storeType", nullable(onboarding.answers() == null ? null : onboarding.answers().storeType()))
                    .set("departments", stringList(onboarding.answers() == null ? null : onboarding.answers().selectedDepartments()));

            ObjectNode storefrontNode = objectMapper.createObjectNode()
                    .put("enabled", sf.enabled())
                    .put("storeThemeId", nullable(sf.storeThemeId()))
                    .put("announcement", nullable(sf.announcement()));
            if (sf.landingContent() != null) {
                storefrontNode.set("landingContent", objectMapper.valueToTree(sf.landingContent()));
            }
            if (designNode != null) {
                storefrontNode.set("design", designNode);
            }

            ObjectNode context = objectMapper.createObjectNode();
            context.set("business", businessNode);
            context.set("branding", brandingNode);
            context.set("onboarding", onboardingNode);
            context.set("storefront", storefrontNode);
            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            return "{\"error\":\"context unavailable\"}";
        }
    }

    private JsonNode parseLenient(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    private JsonNode stringList(List<String> values) {
        var arr = objectMapper.createArrayNode();
        if (values != null) {
            values.stream().filter(v -> v != null && !v.isBlank()).limit(8).forEach(arr::add);
        }
        return arr;
    }

    /** Visible for tests: strict, whitelist-only extraction of the model reply. */
    static StorefrontDesignSuggestResponse parseSuggestions(String content, String requestId) {
        String json = unwrap(content);
        if (json == null) {
            return new StorefrontDesignSuggestResponse(requestId, null, null, null);
        }
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            if (root == null || !root.isObject()) {
                return new StorefrontDesignSuggestResponse(requestId, null, null, null);
            }
            String summary = text(root, "summary", 500);

            JsonNode bk = root.path("brandKit");
            StorefrontDesignSuggestResponse.BrandKitSuggestion brandKit = null;
            if (bk != null && bk.isObject()) {
                String radius = enumValue(bk, "radius", RADIUS_VALUES);
                String buttons = enumValue(bk, "buttons", BUTTONS_VALUES);
                String density = enumValue(bk, "density", DENSITY_VALUES);
                String surface = hexValue(bk, "surface");
                if (radius != null || buttons != null || density != null || surface != null) {
                    brandKit = new StorefrontDesignSuggestResponse.BrandKitSuggestion(
                            radius, buttons, density, surface);
                }
            }

            JsonNode cp = root.path("copy");
            StorefrontDesignSuggestResponse.CopySuggestion copy = null;
            if (cp != null && cp.isObject()) {
                String tagline = text(cp, "tagline", 120);
                String description = text(cp, "description", 1200);
                String announcement = text(cp, "announcement", 200);
                String promoTitle = text(cp, "promoTitle", 120);
                String promoSubtitle = text(cp, "promoSubtitle", 200);
                String coupon = text(cp, "coupon", 40);
                String ctaLabel = text(cp, "ctaLabel", 60);
                String heroHeadline = text(cp, "heroHeadline", 120);
                String heroSubheadline = text(cp, "heroSubheadline", 120);
                String aboutHeading = text(cp, "aboutHeading", 80);
                String socialHeading = text(cp, "socialHeading", 80);
                String contactHeading = text(cp, "contactHeading", 80);
                if (tagline != null || description != null || announcement != null
                        || promoTitle != null || promoSubtitle != null || coupon != null
                        || ctaLabel != null || heroHeadline != null || heroSubheadline != null
                        || aboutHeading != null || socialHeading != null || contactHeading != null) {
                    copy = new StorefrontDesignSuggestResponse.CopySuggestion(
                            tagline, description, announcement, promoTitle, promoSubtitle,
                            coupon, ctaLabel, heroHeadline, heroSubheadline,
                            aboutHeading, socialHeading, contactHeading);
                }
            }

            return new StorefrontDesignSuggestResponse(requestId, summary, brandKit, copy);
        } catch (Exception e) {
            return new StorefrontDesignSuggestResponse(requestId, null, null, null);
        }
    }

    private static String unwrap(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        var m = FENCE.matcher(trimmed);
        if (m.matches()) {
            return m.group(1).strip();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return null;
    }

    private static String text(JsonNode node, String key, int max) {
        JsonNode v = node.get(key);
        if (v == null || !v.isTextual()) {
            return null;
        }
        String s = v.asText().strip();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String enumValue(JsonNode node, String key, List<String> allowed) {
        JsonNode v = node.get(key);
        if (v == null || !v.isTextual()) {
            return null;
        }
        String s = v.asText().strip().toLowerCase(Locale.ROOT);
        return allowed.contains(s) ? s : null;
    }

    private static String hexValue(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || !v.isTextual()) {
            return null;
        }
        String s = v.asText().strip();
        return HEX_COLOR.matcher(s).matches() ? s.toLowerCase(Locale.ROOT) : null;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static final String SYSTEM_PROMPT = """
            You are Kiosk's storefront design assistant for small Kenyan shops.
            The merchant asks you to make their online shop front look or feel a certain way.
            You receive their current store as a JSON object and return suggestions as JSON only.

            Rules:
            - Respond with ONE JSON object and nothing else — no markdown, no prose around it.
            - Suggest only changes you are confident improve the store for the merchant's ask.
            - Use null (or omit the key) for "leave as is". Never invent a change just to fill a field.
            - Keep all copy short, concrete, and natural for a Kenyan shop owner and shopper (Kenyan English; Swahili words are fine when they fit).
            - The theme provides structure; your changes are identity: tokens + copy. Do not restructure sections.

            JSON schema (exactly these keys):
            {
              "summary": "1-2 sentences on what changed and why.",
              "brandKit": {
                "radius": "sharp" | "soft" | "round" | null,
                "buttons": "solid" | "outline" | "pill" | null,
                "density": "compact" | "cozy" | "airy" | null,
                "surface": "#RRGGBB" | null
              },
              "copy": {
                "tagline": "short line under the business name" | null,
                "description": "about the shop, a few sentences" | null,
                "announcement": "notice-bar message" | null,
                "promoTitle": "offer headline" | null,
                "promoSubtitle": "offer subtitle" | null,
                "coupon": "promo code, short" | null,
                "ctaLabel": "offer button label" | null,
                "heroHeadline": "hero headline" | null,
                "heroSubheadline": "hero subheadline" | null,
                "aboutHeading": "about section heading" | null,
                "socialHeading": "social row heading" | null,
                "contactHeading": "contact section heading" | null
              }
            }

            Radius meanings: sharp = crisp straight edges, soft = gently rounded cards, round = friendly pill buttons.
            Density meanings: compact = tighter spacing, cozy = balanced, airy = room to breathe.
            Prefer colors with enough contrast against white and dark surfaces.
            """;
}
