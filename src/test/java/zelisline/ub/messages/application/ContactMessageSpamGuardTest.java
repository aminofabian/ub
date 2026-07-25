package zelisline.ub.messages.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.messages.api.dto.ContactChallengeLine;
import zelisline.ub.messages.api.dto.PublicContactMessageRequest;

class ContactMessageSpamGuardTest {

    @Test
    void acceptsChangeChallenge() {
        assertDoesNotThrow(() -> ContactMessageService.rejectSpam(changeReq(95, null)));
        assertDoesNotThrow(() -> ContactMessageService.rejectSpam(changeReq(95, "")));
    }

    @Test
    void acceptsTotalDiscountVatInventoryAndMissing() {
        assertDoesNotThrow(() -> ContactMessageService.rejectSpam(req(
                "TOTAL",
                List.of(line(1, 80), line(1, 150), line(1, 65)),
                null, null, null, null, 295, null)));
        assertDoesNotThrow(() -> ContactMessageService.rejectSpam(req(
                "DISCOUNT", List.of(), null, 10, 500, null, 450, null)));
        assertDoesNotThrow(() -> ContactMessageService.rejectSpam(req(
                "VAT", List.of(), null, 16, 800, null, 928, null)));
        assertDoesNotThrow(() -> ContactMessageService.rejectSpam(req(
                "INVENTORY", List.of(), null, null, 50, 18, 32, null)));
        assertDoesNotThrow(() -> ContactMessageService.rejectSpam(req(
                "MISSING",
                List.of(line(1, 70), line(1, 80)),
                null, null, 250, null, 100, null)));
        assertDoesNotThrow(() -> ContactMessageService.rejectSpam(req(
                "MULTIPLY", List.of(line(4, 55)), null, null, null, null, 220, null)));
    }

    @Test
    void rejectsWrongAnswer() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> ContactMessageService.rejectSpam(changeReq(100, null)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Check the till maths", ex.getReason());
    }

    @Test
    void rejectsFilledHoneypot() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> ContactMessageService.rejectSpam(changeReq(95, "http://spam.example")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Could not send message", ex.getReason());
    }

    private static PublicContactMessageRequest changeReq(int answer, String website) {
        return req(
                "CHANGE",
                List.of(line(2, 20), line(1, 65)),
                200,
                null,
                null,
                null,
                answer,
                website);
    }

    private static ContactChallengeLine line(int qty, int unitPrice) {
        return new ContactChallengeLine(qty, unitPrice);
    }

    private static PublicContactMessageRequest req(
            String kind,
            List<ContactChallengeLine> lines,
            Integer tendered,
            Integer percent,
            Integer baseAmount,
            Integer secondaryAmount,
            int answer,
            String website
    ) {
        return new PublicContactMessageRequest(
                "Ada",
                "ada@example.com",
                null,
                "Hello",
                null,
                kind,
                lines,
                tendered,
                percent,
                baseAmount,
                secondaryAmount,
                answer,
                website);
    }
}
