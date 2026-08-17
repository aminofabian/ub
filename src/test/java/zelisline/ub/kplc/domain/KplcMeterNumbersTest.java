package zelisline.ub.kplc.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class KplcMeterNumbersTest {

    @Test
    void normalize_stripsSpacesAndAcceptsTypicalMeter() {
        assertEquals("54601443168", KplcMeterNumbers.normalize("54601443168"));
        assertEquals("54601443168", KplcMeterNumbers.normalize("5460 1443 168"));
        assertEquals("54601443168", KplcMeterNumbers.normalize("5460-1443-168"));
    }

    @Test
    void normalize_rejectsTooShortOrNonNumeric() {
        assertNull(KplcMeterNumbers.normalize("1234567"));
        assertNull(KplcMeterNumbers.normalize("meter"));
        assertNull(KplcMeterNumbers.normalize(""));
        assertNull(KplcMeterNumbers.normalize(null));
    }
}
