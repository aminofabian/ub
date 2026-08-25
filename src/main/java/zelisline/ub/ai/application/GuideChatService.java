package zelisline.ub.ai.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
public class GuideChatService {

    private static final int MAX_HISTORY = 8;
    private static final Pattern DRAFT_BLOCK = Pattern.compile(
            "(?s)---DRAFT---\\s*(.*?)\\s*---END DRAFT---");

    private final SokoMindRuntimeService runtimeService;
    private final AiProviderRouter providerRouter;
    private final RouteGuideCatalog routeGuideCatalog;
    private final GuideLiveToolsService liveToolsService;
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
    public AiChatResponse chat(String businessId, String userId, String branchId, AiChatRequest body) {
        if (!runtimeService.isGuideEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SokoMind Guide is disabled. Enable it in Super Admin → Platform → SokoMind.");
        }

        ResolvedSokoMindConfig config = runtimeService.config();
        String skill = inferSkill(body.skill(), body.message());
        AiChatRequest.AiContextPacket ctx = body.context();
        String route = ctx != null ? ctx.route() : null;
        String surface = ctx != null ? ctx.surface() : null;
        String locale = resolveLocale(config, ctx);
        Map<String, String> entities = ctx != null ? ctx.entities() : null;
        String resolvedBranch =
                firstNonBlank(entities != null ? entities.get("branchId") : null, branchId);

        RouteGuideCatalog.RouteGuide guide = routeGuideCatalog.resolve(route, surface);
        GuideLiveToolsService.LiveToolBundle live = liveToolsService.gather(
                businessId, resolvedBranch, guide.surface(), skill, entities, body.message());

        long started = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();

        List<AiChatCompletionRequest.AiChatMessage> messages = new ArrayList<>();
        messages.add(new AiChatCompletionRequest.AiChatMessage(
                "system", buildSystemPrompt(config, guide, locale, skill, live.factsBlock())));
        appendHistory(messages, body.history());
        messages.add(new AiChatCompletionRequest.AiChatMessage("user", body.message().strip()));

        int maxTokens = "draft_message".equals(skill) || "morning_briefing".equals(skill) ? 1200 : 900;
        AiChatCompletionRequest completionRequest =
                new AiChatCompletionRequest(null, messages, 0.3, maxTokens);

        AiRequestLog log = new AiRequestLog();
        log.setId(requestId);
        log.setBusinessId(businessId);
        log.setUserId(userId);
        log.setSkill(skill);
        log.setSurface(guide.surface());
        log.setRoutePath(route);
        log.setCreatedAt(Instant.now());

        try {
            AiChatCompletionResult result =
                    "morning_briefing".equals(skill) || "draft_message".equals(skill)
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
            if ("draft_message".equals(skill) && !suggestions.contains("Draft a polite payment reminder")) {
                suggestions.add(0, "Draft a polite payment reminder SMS");
                suggestions.add(1, "Draft a WhatsApp reply to the latest inbox message");
            }
            if ("morning_briefing".equals(skill)) {
                suggestions = List.of(
                        "What should I focus on first?",
                        "Explain my AP aging",
                        "Which low-stock items matter most?");
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
            String skill,
            String liveFacts
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
        if (liveFacts != null && !liveFacts.isBlank()) {
            sb.append("\nLive shop data (trusted tool results — cite these numbers only):\n");
            sb.append(liveFacts).append('\n');
        }
        if ("draft_message".equals(skill)) {
            sb.append(
                    """
                    Draft rules:
                    - Write a ready-to-send message the user can copy.
                    - Keep it polite, concise, and suitable for SMS/WhatsApp/email in Kenya.
                    - Do not invent invoice numbers or amounts unless present in live shop data.
                    - After a short note to the user, output the draft exactly between markers:
                    ---DRAFT---
                    (message body only)
                    ---END DRAFT---
                    - Never send the message yourself; human must confirm in the UI.
                    """);
        } else if ("morning_briefing".equals(skill)) {
            sb.append(
                    """
                    Morning briefing rules:
                    - Summarize today's pulse, AP pressure, and stock risks from live data.
                    - Give 2–3 prioritized next actions with where to click in the app.
                    - If a live section is missing, say so — do not invent figures.
                    """);
        } else {
            sb.append(
                    """
                    Rules:
                    - Prefer live shop data when present. Do not invent balances, stock counts, prices, or invoices.
                    - Only describe buttons, tabs, screens, and product types that are actually in the page facts above; never invent UI or domain concepts. If the facts do not cover the question, say so and suggest where to check in the app.
                    - If the user asks about the whole app ("how do I use this site", "where is X"), open with a short map of the main areas (Sales/Cashier, Products, Suppliers & bills, Inventory, Analytics) and where to click, then zoom into the current page.
                    - Be a friendly shop assistant: simple and warm, never condescending. Do not talk down even if asked to "explain like I'm 12".
                    - If the user asks for live numbers you do not have, say so and suggest where to look in the UI.
                    - Prefer actionable next steps (which menu / button).
                    - Keep answers under ~180 words unless the user asks for detail.
                    - Never execute payments, payouts, or deletions; you only advise.
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
            // Fallback: treat whole reply as draft if markers missing.
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

    static String inferSkill(String skill, String message) {
        if (skill != null && !skill.isBlank()) {
            String s = skill.trim().toLowerCase(Locale.ROOT);
            return switch (s) {
                case "explain_page", "guide", "error_help", "draft_message", "morning_briefing" ->
                        s.equals("guide") ? "explain_page" : s;
                default -> "explain_page";
            };
        }
        String msg = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (containsAny(msg, "draft", "write a message", "compose", "sms", "whatsapp reply", "email reply")) {
            return "draft_message";
        }
        if (containsAny(msg, "morning", "briefing", "how am i doing", "today's numbers", "daily summary")) {
            return "morning_briefing";
        }
        return "explain_page";
    }

    private static boolean containsAny(String msg, String... needles) {
        for (String n : needles) {
            if (msg.contains(n)) {
                return true;
            }
        }
        return false;
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

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record ParsedReply(String reply, String draftBody) {}
}
