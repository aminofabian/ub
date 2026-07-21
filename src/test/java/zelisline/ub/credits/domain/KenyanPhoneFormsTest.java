package zelisline.ub.credits.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class KenyanPhoneFormsTest {

    @Test
    void toLocal07_fromCommonForms() {
        assertEquals("0714282874", KenyanPhoneForms.toLocal07("0714282874"));
        assertEquals("0714282874", KenyanPhoneForms.toLocal07("254714282874"));
        assertEquals("0714282874", KenyanPhoneForms.toLocal07("+254 714 282 874"));
        assertEquals("0714282874", KenyanPhoneForms.toLocal07("714282874"));
    }

    @Test
    void lookupCandidates_includeBothForms() {
        List<String> c = KenyanPhoneForms.lookupCandidates("0714282874");
        assertTrue(c.contains("0714282874"));
        assertTrue(c.contains("254714282874"));
    }

    @Test
    void looksLikeKenyanMobile() {
        assertTrue(KenyanPhoneForms.looksLikeKenyanMobile("0714282874"));
        assertTrue(KenyanPhoneForms.looksLikeKenyanMobile("254714282874"));
    }
}
