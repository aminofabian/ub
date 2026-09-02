package zelisline.ub.serving.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.ai.application.SokoMindRuntimeService;
import zelisline.ub.ai.application.provider.AiChatCompletionRequest;
import zelisline.ub.ai.application.provider.AiChatCompletionResult;
import zelisline.ub.ai.application.provider.AiProviderRouter;
import zelisline.ub.support.api.dto.SupportMessageDto;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServingAgendaOrganizer {

    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_HEURISTIC = "HEURISTIC";

    static final String SYSTEM_PROMPT = """
            You turn Palmart customer-support threads into a numbered worklist the shop can see and tick off.

            Return JSON only, no markdown:
            {"points":[{"title":"short shop-facing title","detail":"one or two sentences"}]}

            Rules:
            - Each point is one distinct ask, bug, or action from the customer
            - Titles max 80 characters, sequential work the shop understands
            - Detail is concrete: what is stuck, what they asked for
            - Preserve the order things were raised
            - Do not invent problems that are not in the thread
            - 1 to 8 points. A single question is one point
            - Ignore greetings, thank-yous, and Palmart staff replies unless they restate the ask
            """;

    private static final int MAX_POINTS = 8;
    private static final int MAX_TITLE = 120;
    private static final int MAX_DETAIL = 800;
    private static final int MAX_TOKENS = 1200;

    private final AiProviderRouter providerRouter;
    private final SokoMindRuntimeService sokoMindRuntimeService;
    private final ObjectMapper objectMapper;

    public record DraftPoint(String title, String detail) {
    }

    public record Extraction(List<DraftPoint> points, String source) {
    }

    public Extraction extract(String subject, List<SupportMessageDto> messages, String extraBody) {
        String transcript = buildTranscript(subject, messages, extraBody);
        if (transcript.isBlank()) {
            return new Extraction(List.of(new DraftPoint(
                    clip(subject == null || subject.isBlank() ? "Follow up" : subject, MAX_TITLE),
                    "No message text to split — add a point by hand if needed."
            )), SOURCE_HEURISTIC);
        }
        if (sokoMindRuntimeService.isEnabled()) {
            try {
                Extraction ai = extractWithAi(transcript);
                if (ai != null && !ai.points().isEmpty()) {
                    return ai;
                }
            } catch (ResponseStatusException ex) {
                log.info("SokoMind unavailable for serving organize, using heuristic: {}", ex.getReason());
            } catch (RuntimeException ex) {
                log.warn("SokoMind organize failed, using heuristic: {}", ex.getMessage());
            }
        }
        return new Extraction(heuristic(transcript, subject), SOURCE_HEURISTIC);
    }

    private Extraction extractWithAi(String transcript) {
        AiChatCompletionRequest completion = new AiChatCompletionRequest(
                null,
                List.of(
                        new AiChatCompletionRequest.AiChatMessage("system", SYSTEM_PROMPT),
                        new AiChatCompletionRequest.AiChatMessage("user", "Thread:\n" + transcript)
                ),
                0.2,
                MAX_TOKENS
        );
        AiChatCompletionResult result = providerRouter.completeMini(completion);
        List<DraftPoint> points = parsePoints(result.content());
        if (points.isEmpty()) {
            return null;
        }
        return new Extraction(points, SOURCE_AI);
    }

    List<DraftPoint> parsePoints(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(content));
            JsonNode array = root.path("points");
            if (!array.isArray()) {
                return List.of();
            }
            List<DraftPoint> out = new ArrayList<>();
            for (JsonNode row : array) {
                String title = text(row, "title");
                String detail = text(row, "detail");
                if (title == null && detail == null) {
                    continue;
                }
                if (title == null) {
                    title = clip(detail, 80);
                }
                out.add(new DraftPoint(clip(title, MAX_TITLE), clip(detail, MAX_DETAIL)));
                if (out.size() >= MAX_POINTS) {
                    break;
                }
            }
            return out;
        } catch (Exception ex) {
            log.debug("Could not parse organize JSON: {}", ex.getMessage());
            return List.of();
        }
    }

    static List<DraftPoint> heuristic(String transcript, String subject) {
        List<String> chunks = splitChunks(transcript);
        List<DraftPoint> out = new ArrayList<>();
        for (String chunk : chunks) {
            String cleaned = chunk.replaceAll("\\s+", " ").trim();
            if (cleaned.length() < 8) {
                continue;
            }
            String title = titleFrom(cleaned);
            out.add(new DraftPoint(clip(title, MAX_TITLE), clip(cleaned, MAX_DETAIL)));
            if (out.size() >= MAX_POINTS) {
                break;
            }
        }
        if (out.isEmpty()) {
            String fallback = clip(subject == null || subject.isBlank() ? "Follow up" : subject, MAX_TITLE);
            out.add(new DraftPoint(fallback, clip(transcript, MAX_DETAIL)));
        }
        return out;
    }

    static List<String> splitChunks(String transcript) {
        String[] lines = transcript.split("\\R");
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                flush(current, chunks);
                continue;
            }
            boolean numbered = line.matches("(?i)^(?:\\d+[\\.)]|[-*•])\\s+.+");
            if (numbered) {
                flush(current, chunks);
                chunks.add(line.replaceFirst("(?i)^(?:\\d+[\\.)]|[-*•])\\s+", ""));
                continue;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
            if (line.endsWith("?") && current.length() > 12) {
                flush(current, chunks);
            }
        }
        flush(current, chunks);
        List<String> numbered = splitInlineNumbered(transcript);
        if (numbered.size() > chunks.size()) {
            return numbered;
        }
        if (chunks.size() <= 1) {
            if (numbered.size() > 1) {
                return numbered;
            }
            return splitOnQuestions(transcript);
        }
        return chunks;
    }

    static List<String> splitInlineNumbered(String text) {
        String stripped = text.replaceAll("(?im)^(?:TENANT|VISITOR|SHOPPER|CUSTOMER|FORM|SUBJECT):\\s*", "");
        String[] byLine = stripped.split("(?m)(?=^\\s*\\d+[\\.)]\\s+)");
        List<String> fromLines = cleanNumberedParts(byLine);
        String[] inline = stripped.split("(?<=[a-zA-Z.?!])\\s+(?=\\d+[\\.)]\\s+)");
        List<String> fromInline = cleanNumberedParts(inline);
        return fromInline.size() > fromLines.size() ? fromInline : fromLines;
    }

    private static List<String> cleanNumberedParts(String[] parts) {
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.replaceFirst("^\\s*\\d+[\\.)]\\s+", "").trim();
            if (trimmed.length() >= 8) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static List<String> splitOnQuestions(String transcript) {
        String[] parts = transcript.split("(?<=\\?)");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.length() >= 8) {
                out.add(trimmed);
            }
        }
        return out.isEmpty() ? List.of(transcript.trim()) : out;
    }

    private static void flush(StringBuilder current, List<String> chunks) {
        if (current.length() == 0) {
            return;
        }
        chunks.add(current.toString().trim());
        current.setLength(0);
    }

    static String buildTranscript(String subject, List<SupportMessageDto> messages, String extraBody) {
        StringBuilder sb = new StringBuilder();
        if (subject != null && !subject.isBlank()) {
            sb.append("Subject: ").append(subject.trim()).append('\n');
        }
        if (extraBody != null && !extraBody.isBlank()) {
            sb.append("Form: ").append(extraBody.trim()).append('\n');
        }
        if (messages != null) {
            for (SupportMessageDto message : messages) {
                if (message == null || message.body() == null || message.body().isBlank()) {
                    continue;
                }
                String kind = message.messageKind() == null ? "TEXT" : message.messageKind();
                if ("WELCOME_CARD".equalsIgnoreCase(kind)) {
                    continue;
                }
                String who = message.senderType() == null ? "CUSTOMER" : message.senderType();
                sb.append(who).append(": ").append(message.body().trim()).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static String titleFrom(String cleaned) {
        int cut = cleaned.indexOf('?');
        String first = cut >= 0 && cut < 90 ? cleaned.substring(0, cut + 1) : cleaned;
        int period = first.indexOf('.');
        if (period > 12 && period < 80) {
            first = first.substring(0, period);
        }
        return first;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        String s = value.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    static String extractJsonObject(String content) {
        String trimmed = content.trim();
        int fence = trimmed.indexOf("```");
        if (fence >= 0) {
            int start = trimmed.indexOf('{', fence);
            if (start > fence) {
                trimmed = trimmed.substring(start);
            }
        }
        int start = trimmed.indexOf('{');
        if (start < 0) {
            return "{}";
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return trimmed.substring(start, i + 1);
                }
            }
        }
        return "{}";
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max).trim();
    }
}
