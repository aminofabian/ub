package zelisline.ub.ai.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import zelisline.ub.ai.application.provider.AiChatCompletionRequest;
import zelisline.ub.ai.application.provider.AiChatCompletionResult;
import zelisline.ub.ai.application.provider.AiProviderRouter;

/**
 * Drafts a WhatsApp reply for a shop assistant from a conversation's recent turns, optionally
 * grounded in retrieved knowledge-base chunks.
 *
 * <p>Reuses the platform SokoMind provider stack ({@link AiProviderRouter}); when SokoMind is
 * disabled or unconfigured the router throws {@code 503} and the caller surfaces it. No data is
 * persisted here — the draft is returned to the agent (or the auto-reply caller) for use.
 *
 * <p>See {@code docs/scopes/whatsapp-crm/SCOPE.md} §M5 (AI reply, knowledge base).
 */
@Service
@RequiredArgsConstructor
public class WhatsAppReplyAiService {

    private static final int MAX_TURNS = 20;
    private static final int MAX_TOKENS = 400;

    private final AiProviderRouter providerRouter;

    /** One conversation turn; {@code direction} is {@code inbound} or {@code outbound}. */
    public record Turn(String direction, String text) {
    }

    public String draftReply(String shopName, List<Turn> history) {
        return draftReply(shopName, history, List.of());
    }

    public String draftReply(String shopName, List<Turn> history, List<String> knowledge) {
        List<AiChatCompletionRequest.AiChatMessage> messages = new ArrayList<>();
        messages.add(new AiChatCompletionRequest.AiChatMessage("system", systemPrompt(shopName, knowledge)));

        List<Turn> turns = history == null ? List.of() : history;
        int from = Math.max(0, turns.size() - MAX_TURNS);
        for (Turn turn : turns.subList(from, turns.size())) {
            if (turn == null || turn.text() == null || turn.text().isBlank()) {
                continue;
            }
            String role = "outbound".equalsIgnoreCase(turn.direction()) ? "assistant" : "user";
            messages.add(new AiChatCompletionRequest.AiChatMessage(role, turn.text()));
        }

        if (messages.size() == 1) {
            // No usable history — nothing to reply to.
            return "";
        }

        AiChatCompletionResult result = providerRouter.completeMini(
                new AiChatCompletionRequest(null, messages, 0.4, MAX_TOKENS));
        String content = result.content();
        return content == null ? "" : content.trim();
    }

    private static String systemPrompt(String shopName, List<String> knowledge) {
        String shop = shopName == null || shopName.isBlank() ? "the shop" : shopName;
        StringBuilder sb = new StringBuilder();
        sb.append("You are a helpful WhatsApp assistant for ").append(shop).append(", a Kenyan shop. ")
          .append("Draft a short, friendly reply to the customer's most recent message, ")
          .append("consistent with the earlier turns. Use plain Kenyan English, 1-3 sentences, ")
          .append("and never invent prices, stock, or policies. ");
        if (knowledge != null && !knowledge.isEmpty()) {
            sb.append("Use ONLY the following shop knowledge to answer; if it is not covered, ")
              .append("say a team member will follow up. If the message needs a human (complaint, ")
              .append("refund, unclear request), reply with the single word HANDOFF.\n\nShop knowledge:\n");
            for (String piece : knowledge) {
                sb.append("- ").append(piece).append('\n');
            }
        }
        sb.append("Return only the reply text.");
        return sb.toString();
    }
}
