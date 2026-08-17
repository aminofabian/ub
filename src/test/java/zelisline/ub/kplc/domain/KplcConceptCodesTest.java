package zelisline.ub.kplc.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class KplcConceptCodesTest {

    @Test
    void knownCodesHavePlainLanguageLabels() {
        assertEquals("Lifeline electricity (0–50 kWh)", KplcConceptCodes.label("RESSTEP0"));
        assertEquals("VAT", KplcConceptCodes.label("vat"));
        assertEquals("TAX", KplcConceptCodes.kind("VAT"));
        assertEquals("ENERGY", KplcConceptCodes.kind("RESSTEP1"));
        assertEquals("NEWCODE", KplcConceptCodes.label("NEWCODE"));
        assertEquals("OTHER", KplcConceptCodes.kind("NEWCODE"));
    }
}
