package zelisline.ub.ai.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
public class SupplierGuideChatService {

    private static final int MAX_HISTORY = 8;
    private static final Pattern DRAFT_BLOCK = Pattern.compile(
            "(?s)---DRAFT---\\s*(.*?)\\s*---END DRAFT---");

    private final SokoMindRuntimeService runtimeService;
    private final AiProviderRouter providerRouter;
    private final RouteGuideCatalog routeGuideCatalog;
    private final SupplierGuideLiveToolsService liveToolsService;
    private final AiRequestLogRepository requestLogRepository;

    @Transactional(readOnly = true)
    public AiStatusResponse status() {
        return runtimeService.isGuideEnabled()
                ? new AiStatusResponse(
                        true,
                        true,
                        false,
                        false,
                        runtimeService.config().primaryProviderConfigured(),
                        runtimeService.config().primaryProvider(),
                        runtimeService.config().defaultLocale())
                : new AiStatusResponse(
                        runtimeService.config().enabled(),
                        false,
                        false,
                        false,
                        runtimeService.config().primaryProviderConfigured(),
                        runtimeService.config().primaryProvider(),
                        runtimeService.config().defaultLocale());
    }

    @Transactional
    public AiChatResponse chat(String marketplaceSupplierId, String userId, AiChatRequest body) {
        if (!runtimeService.isGuideEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SokoMind Guide is disabled. Enable it in Super Admin → Platform → SokoMind.");
        }

        ResolvedSokoMindConfig config = runtimeService.config();
        String skill = GuideChatService.inferSkill(body.skill(), body.message());
        AiChatRequest.AiContextPacket ctx = body.context();
        String route = ctx != null ? ctx.route() : null;
        String surface = ctx != null ? ctx.surface() : "supplier-portal";
        String locale = resolveLocale(config, ctx);

        RouteGuideCatalog.RouteGuide guide = routeGuideCatalog.resolve(route, surface);
        SupplierGuideLiveToolsService.LiveToolBundle live =
                liveToolsService.gather(marketplaceSupplierId, guide.surface(), skill, body.message());

        long started = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();

        List<AiChatCompletionRequest.AiChatMessage> messages = new ArrayList<>();
        messages.add(new AiChatCompletionRequest.AiChatMessage(
                "system", buildSystemPrompt(config, guide, locale, skill, live.factsBlock())));
        appendHistory(messages, body.history());
        messages.add(new AiChatCompletionRequest.AiChatMessage("user", body.message().strip()));

        AiChatCompletionRequest completionRequest =
                new AiChatCompletionRequest(null, messages, 0.3, "draft_message".equals(skill) ? 1200 : 900);

        AiRequestLog log = new AiRequestLog();
        log.setId(requestId);
        log.setBusinessId(marketplaceSupplierId); // audit key = marketplace supplier id for portal
        log.setUserId(userId);
        log.setSkill(skill);
        log.setSurface(guide.surface());
        log.setRoutePath(route);
        log.setCreatedAt(Instant.now());

        try {
            AiChatCompletionResult result = "draft_message".equals(skill) || "morning_briefing".equals(skill)
                    ? providerRouter.completeSmart(completionRequest)
                    : providerRouter.completeMini(completionRequest);
            long latency = System.currentTimeMillis() - started;
            log.setSuccess(true);
            log.setProvider(result.provider());
            log.setModel(result.model());
            log.setPromptTokens(result.promptTokens());
            log.setCompletionTokens(result.completionTokens());
            log.setLatencyMs((int) Math.min(latency, Integer.MAX_VALUE));
            requestLogRepository.save(log);

            ParsedReply parsed = parseReply(result.content(), skill);
            List<String> suggestions = new ArrayList<>(guide.suggestions());
            if ("draft_message".equals(skill)) {
                suggestions.add(0, "Draft a polite payment follow-up to a shop");
            }

            return new AiChatResponse(
                    requestId,
                    parsed.reply(),
                    skill,
                    guide.surface(),
                    suggestions,
                    result.provider(),
                    result.model(),
                    latency,
                    live.toolsUsed(),
                    !live.toolsUsed().isEmpty(),
                    parsed.draftBody());
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
    public void feedback(String marketplaceSupplierId, AiFeedbackRequest body) {
        AiRequestLog log = requestLogRepository
                .findByIdAndBusinessId(body.requestId(), marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AI request not found"));
        log.setFeedback(body.feedback().toLowerCase(Locale.ROOT));
        requestLogRepository.save(log);
    }

    private String buildSystemPrompt(
            ResolvedSokoMindConfig config,
            RouteGuideCatalog.RouteGuide guide,
            String locale,
            String skill,
            String liveFacts
    ) {
        String lang = locale.toLowerCase(Locale.ROOT).startsWith("sw")
                ? "Respond in clear Swahili (Kenya), short sentences."
                : "Respond in clear Kenyan English, short sentences.";

        StringBuilder sb = new StringBuilder();
        sb.append("You are SokoMind Guide for the Kiosk Supplier Portal.\n");
        sb.append(lang).append('\n');
        sb.append("Skill: ").append(skill).append('\n');
        sb.append("Page: ").append(guide.title()).append(" (").append(guide.surface()).append(")\n");
        sb.append("Page facts:\n- ").append(guide.summary()).append('\n');
        for (String tip : guide.tips()) {
            sb.append("- ").append(tip).append('\n');
        }
        if (liveFacts != null && !liveFacts.isBlank()) {
            sb.append("\nLive supplier data (trusted — cite these numbers only):\n");
            sb.append(liveFacts).append('\n');
        }
        if ("draft_message".equals(skill)) {
            sb.append(
                    """
                    Draft rules:
                    - Write a ready-to-send message to a connected shop.
                    - Be polite and concise (SMS/WhatsApp friendly).
                    - Do not invent amounts unless present in live data.
                    - After a short note, output:
                    ---DRAFT---
                    (message body only)
                    ---END DRAFT---
                    - Never send the message yourself.
                    """);
        } else {
            sb.append(
                    """
                    Rules:
                    - Prefer live supplier data when present. Do not invent shop sales or stock.
                    - You only see AP projections for shops you supply — not their full POS.
                    - Never change payout details or approve payments; advise only.
                    - Keep answers under ~180 words unless asked for detail.
                    """);
        }
        if (config.systemPromptExtra() != null && !config.systemPromptExtra().isBlank()) {
            sb.append("\nPlatform notes:\n").append(config.systemPromptExtra().strip()).append('\n');
        }
        return sb.toString();
    }

    private static ParsedReply parseReply(String content, String skill) {
        if (content == null) {
            return new ParsedReply("", null);
        }
        Matcher matcher = DRAFT_BLOCK.matcher(content);
        if (matcher.find()) {
            String draft = matcher.group(1).trim();
            String without = (content.substring(0, matcher.start()) + content.substring(matcher.end())).trim();
            if (without.isBlank()) {
                without = "Here is a draft you can copy. Review before sending.";
            }
            return new ParsedReply(without, draft.isBlank() ? null : draft);
        }
        if ("draft_message".equals(skill)) {
            return new ParsedReply("Draft ready — review before sending.", content.trim());
        }
        return new ParsedReply(content.trim(), null);
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

    private record ParsedReply(String reply, String draftBody) {}
}
