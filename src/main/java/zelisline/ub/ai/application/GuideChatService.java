package zelisline.ub.ai.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.api.dto.AiChatRequest;
import zelisline.ub.ai.api.dto.AiChatResponse;
import zelisline.ub.ai.api.dto.AiFeedbackRequest;
import zelisline.ub.ai.api.dto.AiStatusResponse;
import zelisline.ub.ai.application.provider.AiChatCompletionRequest;
import zelisline.ub.ai.application.provider.AiChatCompletionResult;
import zelisline.ub.ai.application.provider.AiProviderRouter;
import zelisline.ub.ai.domain.AiRequestLog;
import zelisline.ub.ai.repository.AiRequestLogRepository;

@Service
@RequiredArgsConstructor
public class GuideChatService {

    private static final int MAX_HISTORY = 8;

    private final SokoMindRuntimeService runtimeService;
    private final AiProviderRouter providerRouter;
    private final RouteGuideCatalog routeGuideCatalog;
    private final AiRequestLogRepository requestLogRepository;

    @Transactional(readOnly = true)
    public AiStatusResponse status() {
        ResolvedSokoMindConfig config = runtimeService.config();
        return new AiStatusResponse(
                config.enabled(),
                config.enabled() && config.guideEnabled(),
                config.enabled() && config.brainEnabled(),
                config.enabled() && config.eyeEnabled(),
                config.primaryProviderConfigured(),
                config.primaryProvider(),
                config.defaultLocale());
    }

    @Transactional
    public AiChatResponse chat(String businessId, String userId, AiChatRequest body) {
        if (!runtimeService.isGuideEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SokoMind Guide is disabled. Enable it in Super Admin → Platform → SokoMind.");
        }

        ResolvedSokoMindConfig config = runtimeService.config();
        String skill = normalizeSkill(body.skill());
        AiChatRequest.AiContextPacket ctx = body.context();
        String route = ctx != null ? ctx.route() : null;
        String surface = ctx != null ? ctx.surface() : null;
        String locale = resolveLocale(config, ctx);

        RouteGuideCatalog.RouteGuide guide = routeGuideCatalog.resolve(route, surface);
        long started = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();

        List<AiChatCompletionRequest.AiChatMessage> messages = new ArrayList<>();
        messages.add(new AiChatCompletionRequest.AiChatMessage("system", buildSystemPrompt(config, guide, locale, skill)));
        appendHistory(messages, body.history());
        messages.add(new AiChatCompletionRequest.AiChatMessage("user", body.message().strip()));

        AiChatCompletionRequest completionRequest =
                new AiChatCompletionRequest(null, messages, 0.3, 900);

        AiRequestLog log = new AiRequestLog();
        log.setId(requestId);
        log.setBusinessId(businessId);
        log.setUserId(userId);
        log.setSkill(skill);
        log.setSurface(guide.surface());
        log.setRoutePath(route);
        log.setCreatedAt(Instant.now());

        try {
            AiChatCompletionResult result = providerRouter.completeMini(completionRequest);
            long latency = System.currentTimeMillis() - started;
            log.setSuccess(true);
            log.setProvider(result.provider());
            log.setModel(result.model());
            log.setPromptTokens(result.promptTokens());
            log.setCompletionTokens(result.completionTokens());
            log.setLatencyMs((int) Math.min(latency, Integer.MAX_VALUE));
            requestLogRepository.save(log);

            return new AiChatResponse(
                    requestId,
                    result.content(),
                    skill,
                    guide.surface(),
                    guide.suggestions(),
                    result.provider(),
                    result.model(),
                    latency);
        } catch (RuntimeException ex) {
            long latency = System.currentTimeMillis() - started;
            log.setSuccess(false);
            log.setLatencyMs((int) Math.min(latency, Integer.MAX_VALUE));
            log.setErrorMessage(truncate(ex.getMessage(), 500));
            requestLogRepository.save(log);
            throw ex;
        }
    }

    @Transactional
    public void feedback(String businessId, AiFeedbackRequest body) {
        AiRequestLog log = requestLogRepository
                .findByIdAndBusinessId(body.requestId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AI request not found"));
        log.setFeedback(body.feedback().toLowerCase(Locale.ROOT));
        requestLogRepository.save(log);
    }

    private String buildSystemPrompt(
            ResolvedSokoMindConfig config,
            RouteGuideCatalog.RouteGuide guide,
            String locale,
            String skill
    ) {
        String lang = locale.toLowerCase(Locale.ROOT).startsWith("sw")
                ? "Respond in clear Swahili (Kenya), short sentences."
                : "Respond in clear Kenyan English, short sentences.";

        StringBuilder sb = new StringBuilder();
        sb.append("You are SokoMind Guide for Kiosk (Palmart) — a POS and back-office for Kenyan shops.\n");
        sb.append(lang).append('\n');
        sb.append("Skill: ").append(skill).append('\n');
        sb.append("Page: ").append(guide.title()).append(" (").append(guide.surface()).append(")\n");
        sb.append("Page facts (trusted):\n- ").append(guide.summary()).append('\n');
        for (String tip : guide.tips()) {
            sb.append("- ").append(tip).append('\n');
        }
        sb.append(
                """
                Rules:
                - Only use page facts above and the user's message. Do not invent balances, stock counts, prices, or invoices.
                - If the user asks for live numbers you do not have, say you cannot see them yet and suggest where to look in the UI.
                - Prefer actionable next steps (which menu / button).
                - Keep answers under ~180 words unless the user asks for detail.
                - Never execute payments, payouts, or deletions; you only advise.
                """);
        if (config.systemPromptExtra() != null && !config.systemPromptExtra().isBlank()) {
            sb.append("\nPlatform notes:\n").append(config.systemPromptExtra().strip()).append('\n');
        }
        return sb.toString();
    }

    private static void appendHistory(
            List<AiChatCompletionRequest.AiChatMessage> messages,
            List<AiChatRequest.AiHistoryMessage> history
    ) {
        if (history == null || history.isEmpty()) {
            return;
        }
        int start = Math.max(0, history.size() - MAX_HISTORY);
        for (int i = start; i < history.size(); i++) {
            AiChatRequest.AiHistoryMessage h = history.get(i);
            if (h == null || h.role() == null || h.content() == null) {
                continue;
            }
            String role = h.role().trim().toLowerCase(Locale.ROOT);
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            messages.add(new AiChatCompletionRequest.AiChatMessage(role, h.content().strip()));
        }
    }

    private static String normalizeSkill(String skill) {
        if (skill == null || skill.isBlank()) {
            return "explain_page";
        }
        String s = skill.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "explain_page", "guide", "error_help", "draft_message" -> s.equals("guide") ? "explain_page" : s;
            default -> "explain_page";
        };
    }

    private static String resolveLocale(ResolvedSokoMindConfig config, AiChatRequest.AiContextPacket ctx) {
        if (ctx != null && ctx.locale() != null && !ctx.locale().isBlank()) {
            return ctx.locale().trim();
        }
        if (config.defaultLocale() != null && !config.defaultLocale().isBlank()) {
            return config.defaultLocale().trim();
        }
        return "en-KE";
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
