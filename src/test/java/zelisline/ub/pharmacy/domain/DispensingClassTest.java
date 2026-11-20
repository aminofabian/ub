package zelisline.ub.pharmacy.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class DispensingClassTest {

    @Test
    void parse_acceptsCaseSpacesAndHyphens() {
        assertEquals(DispensingClass.OTC, DispensingClass.parse(" otc "));
        assertEquals(DispensingClass.PRESCRIPTION_ONLY, DispensingClass.parse("prescription-only"));
        assertEquals(DispensingClass.PRESCRIPTION_ONLY, DispensingClass.parse("Prescription Only"));
        assertEquals(DispensingClass.CONTROLLED, DispensingClass.parse("CONTROLLED"));
    }

    @Test
    void parse_rejectsBlankAndUnknown() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> DispensingClass.parse(" ")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> DispensingClass.parse("pom")).getStatusCode());
    }

    @Test
    void onlyPrescriptionAndControlledNeedARecord() {
        assertFalse(DispensingClass.OTC.needsDispensingRecord());
        assertFalse(DispensingClass.UNCLASSIFIED.needsDispensingRecord());
        assertTrue(DispensingClass.PRESCRIPTION_ONLY.needsDispensingRecord());
        assertTrue(DispensingClass.CONTROLLED.needsDispensingRecord());
    }

    @Test
    void everythingButOtcIsBlockedOnline() {
        assertFalse(DispensingClass.OTC.blockedOnline());
        assertTrue(DispensingClass.UNCLASSIFIED.blockedOnline());
        assertTrue(DispensingClass.PRESCRIPTION_ONLY.blockedOnline());
        assertTrue(DispensingClass.CONTROLLED.blockedOnline());
    }
}
