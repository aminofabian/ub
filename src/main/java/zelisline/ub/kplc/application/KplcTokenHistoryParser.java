package zelisline.ub.kplc.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.kplc.api.dto.PublicKplcConceptResponse;
import zelisline.ub.kplc.api.dto.PublicKplcTokenResponse;
import zelisline.ub.kplc.domain.KplcConceptCodes;

/**
 * Kenya Power history arrives nested ({@code data.data[].colPrepayment[]})
 * rather than as a flat token array. Walk the known wrappers and keep any
 * object that already looks like a token.
 */
final class KplcTokenHistoryParser {

    static final int MAX_TOKENS = 40;

    private KplcTokenHistoryParser() {
    }

    static List<PublicKplcTokenResponse> parse(ObjectMapper objectMapper, String body) throws Exception {
        JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
        if (root.has("success") && root.path("success").isBoolean() && !root.path("success").asBoolean()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not load tokens for this meter.");
        }
        List<PublicKplcTokenResponse> out = new ArrayList<>();
        collect(root, out);
        out.sort(Comparator.comparing(PublicKplcTokenResponse::purchasedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (out.size() > MAX_TOKENS) {
            return List.copyOf(out.subList(0, MAX_TOKENS));
        }
        return List.copyOf(out);
    }

    private static void collect(JsonNode node, List<PublicKplcTokenResponse> out) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collect(child, out);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if (looksLikeToken(node)) {
            PublicKplcTokenResponse token = toToken(node);
            if (token != null) {
                out.add(token);
            }
            return;
        }
        collect(node.get("colPrepayment"), out);
        collect(node.get("tokens"), out);
        collect(node.get("data"), out);
    }

    private static boolean looksLikeToken(JsonNode node) {
        String tokenNo = text(node, "tokenNo");
        return tokenNo != null && !tokenNo.isBlank();
    }

    private static PublicKplcTokenResponse toToken(JsonNode row) {
        String tokenNo = text(row, "tokenNo");
        if (tokenNo == null || tokenNo.isBlank()) {
            return null;
        }
        Instant purchasedAt = timestamp(row.path("trnTimestamp"));
        List<PublicKplcConceptResponse> concepts = new ArrayList<>();
        JsonNode breakdown = row.path("concepts");
        if (breakdown.isArray()) {
            for (JsonNode item : breakdown) {
                String code = text(item, "codConcept");
                BigDecimal amount = decimal(item.path("amount"));
                if (code == null && amount == null) {
                    continue;
                }
                String safeCode = code == null ? "" : code;
                concepts.add(new PublicKplcConceptResponse(
                        safeCode,
                        KplcConceptCodes.label(safeCode),
                        KplcConceptCodes.kind(safeCode),
                        amount));
            }
        }
        return new PublicKplcTokenResponse(
                purchasedAt,
                decimal(row.path("trnAmount")),
                decimal(row.path("trnUnits")),
                tokenNo.trim(),
                text(row, "recptNo"),
                text(row, "pMethod"),
                List.copyOf(concepts));
    }

    private static Instant timestamp(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        long ms = 0;
        if (node.isNumber()) {
            ms = node.asLong();
        } else {
            String raw = node.asText();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                ms = Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return ms > 0 ? Instant.ofEpochMilli(ms) : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String raw = value.asText();
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.decimalValue();
        }
        String raw = node.asText();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
