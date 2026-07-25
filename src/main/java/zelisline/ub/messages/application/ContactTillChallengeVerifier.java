package zelisline.ub.messages.application;

import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.messages.api.dto.ContactChallengeLine;
import zelisline.ub.messages.api.dto.PublicContactMessageRequest;

final class ContactTillChallengeVerifier {

    private ContactTillChallengeVerifier() {}

    static void verify(PublicContactMessageRequest body) {
        if (body.website() != null && !body.website().isBlank()) {
            throw badRequest("Could not send message");
        }
        String kind = body.challengeKind() == null
                ? ""
                : body.challengeKind().trim().toUpperCase(Locale.ROOT);
        List<ContactChallengeLine> lines = body.lines() == null ? List.of() : body.lines();
        Integer expected = switch (kind) {
            case "TOTAL", "MULTIPLY" -> requireLinesTotal(lines);
            case "CHANGE" -> changeDue(lines, body.tendered());
            case "DISCOUNT" -> discountedTotal(body.baseAmount(), body.percent());
            case "MISSING" -> missingItem(body.baseAmount(), lines);
            case "VAT" -> withVat(body.baseAmount(), body.percent());
            case "INVENTORY" -> remainingStock(body.baseAmount(), body.secondaryAmount());
            default -> null;
        };
        if (expected == null || body.challengeAnswer() == null || !expected.equals(body.challengeAnswer())) {
            throw badRequest("Check the till maths");
        }
    }

    private static Integer requireLinesTotal(List<ContactChallengeLine> lines) {
        if (lines.isEmpty()) {
            return null;
        }
        return lineTotal(lines);
    }

    private static Integer changeDue(List<ContactChallengeLine> lines, Integer tendered) {
        if (lines.isEmpty() || tendered == null) {
            return null;
        }
        int total = lineTotal(lines);
        int change = tendered - total;
        return change < 0 ? null : change;
    }

    private static Integer discountedTotal(Integer baseAmount, Integer percent) {
        if (baseAmount == null || percent == null) {
            return null;
        }
        return baseAmount - (baseAmount * percent / 100);
    }

    private static Integer missingItem(Integer billTotal, List<ContactChallengeLine> lines) {
        if (billTotal == null || lines.isEmpty()) {
            return null;
        }
        int known = lineTotal(lines);
        int missing = billTotal - known;
        return missing < 0 ? null : missing;
    }

    private static Integer withVat(Integer baseAmount, Integer percent) {
        if (baseAmount == null || percent == null) {
            return null;
        }
        return baseAmount + (baseAmount * percent / 100);
    }

    private static Integer remainingStock(Integer start, Integer sold) {
        if (start == null || sold == null || sold > start) {
            return null;
        }
        return start - sold;
    }

    private static int lineTotal(List<ContactChallengeLine> lines) {
        int total = 0;
        for (ContactChallengeLine line : lines) {
            if (line == null || line.qty() == null || line.unitPrice() == null) {
                continue;
            }
            total += line.qty() * line.unitPrice();
        }
        return total;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
