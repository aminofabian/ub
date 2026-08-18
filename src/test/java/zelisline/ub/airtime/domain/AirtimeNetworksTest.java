package zelisline.ub.airtime.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AirtimeNetworksTest {

    @Test
    void mapsKenyanPrefixesToNetworks() {
        assertEquals(AirtimeNetworks.SAFARICOM, AirtimeNetworks.detect("0714282874"));
        assertEquals(AirtimeNetworks.SAFARICOM, AirtimeNetworks.detect("0700123456"));
        assertEquals(AirtimeNetworks.SAFARICOM, AirtimeNetworks.detect("0740123456"));
        assertEquals(AirtimeNetworks.SAFARICOM, AirtimeNetworks.detect("0110123456"));
        assertEquals(AirtimeNetworks.SAFARICOM, AirtimeNetworks.detect("0140123456"));
        assertEquals(AirtimeNetworks.SAFARICOM, AirtimeNetworks.detect("0180123456"));
        assertEquals(AirtimeNetworks.AIRTEL, AirtimeNetworks.detect("0730123456"));
        assertEquals(AirtimeNetworks.AIRTEL, AirtimeNetworks.detect("0785123456"));
        assertEquals(AirtimeNetworks.AIRTEL, AirtimeNetworks.detect("0100123456"));
        assertEquals(AirtimeNetworks.TELKOM, AirtimeNetworks.detect("0770123456"));
        assertEquals(AirtimeNetworks.EQUITEL, AirtimeNetworks.detect("0763123456"));
        assertEquals(AirtimeNetworks.EQUITEL, AirtimeNetworks.detect("0766123456"));
        assertEquals(AirtimeNetworks.JTL, AirtimeNetworks.detect("0747123456"));
        assertNull(AirtimeNetworks.detect("0780123456"));
        assertNull(AirtimeNetworks.detect("0744123456"));
    }
}
