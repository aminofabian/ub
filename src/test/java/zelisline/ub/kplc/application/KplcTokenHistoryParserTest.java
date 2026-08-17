package zelisline.ub.kplc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.kplc.api.dto.PublicKplcTokenResponse;

class KplcTokenHistoryParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void nestedColPrepayment_readsTokensNewestFirst() throws Exception {
        List<PublicKplcTokenResponse> tokens = KplcTokenHistoryParser.parse(mapper, """
                {
                  "success": true,
                  "data": {
                    "data": [{
                      "accountReference": "21606496",
                      "colPrepayment": [
                        {
                          "trnTimestamp": 1786911985000,
                          "tokenNo": "37456605149858621649",
                          "recptNo": "RCT1786911985122",
                          "pMethod": "Cash",
                          "trnUnits": 10.7,
                          "trnAmount": 300,
                          "concepts": [{"codConcept": "RESSTEP0", "amount": 196.94}]
                        },
                        {
                          "trnTimestamp": 1786537596000,
                          "tokenNo": "27328836806753794247",
                          "recptNo": "RCT1786537596931",
                          "pMethod": "Cash",
                          "trnUnits": 17.7,
                          "trnAmount": 500
                        }
                      ],
                      "prepayment": true
                    }]
                  }
                }
                """);

        assertEquals(2, tokens.size());
        assertEquals("37456605149858621649", tokens.get(0).tokenNo());
        assertEquals(Instant.ofEpochMilli(1786911985000L), tokens.get(0).purchasedAt());
        assertThat(tokens.get(0).amount()).isEqualByComparingTo("300");
        assertThat(tokens.get(0).units()).isEqualByComparingTo("10.7");
        assertEquals("Cash", tokens.get(0).paymentMethod());
        assertEquals(1, tokens.get(0).concepts().size());
        assertEquals("27328836806753794247", tokens.get(1).tokenNo());
    }

    @Test
    void flatTokenArray_stillParses() throws Exception {
        List<PublicKplcTokenResponse> tokens = KplcTokenHistoryParser.parse(mapper, """
                {
                  "success": true,
                  "data": [
                    {
                      "tokenNo": "11112222333344445555",
                      "trnAmount": 100,
                      "trnUnits": 4,
                      "trnTimestamp": 1700000000000
                    }
                  ]
                }
                """);

        assertEquals(1, tokens.size());
        assertEquals("11112222333344445555", tokens.get(0).tokenNo());
    }

    @Test
    void successFalse_throws() {
        assertThrows(ResponseStatusException.class, () -> KplcTokenHistoryParser.parse(mapper, """
                {"success":false,"message":"Meter not found"}
                """));
    }

    @Test
    void objectWithoutTokens_isEmptyNotError() throws Exception {
        List<PublicKplcTokenResponse> tokens = KplcTokenHistoryParser.parse(mapper, """
                {"success":true,"data":{"data":[{"colPrepayment":[],"prepayment":true}]}}
                """);
        assertTrue(tokens.isEmpty());
    }
}
