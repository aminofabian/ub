package zelisline.ub.payments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class KopokopoPayerPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void extractsRootJsonApiBuygoods() throws Exception {
        var root = mapper.readTree("""
                {
                  "id": "7dc1a9e4-e0e6-4bf4-bd47-0e698cb57db9",
                  "type": "buygoods_transaction_received",
                  "attributes": {
                    "first_name": "JOHN",
                    "last_name": "DOE",
                    "phone_number": "+2547XXXXX123"
                  }
                }
                """);
        var extracted = KopokopoPayerPayload.extract(root);
        assertThat(extracted.firstName()).isEqualTo("JOHN");
        assertThat(extracted.lastName()).isEqualTo("DOE");
        assertThat(extracted.phoneRaw()).isEqualTo("+2547XXXXX123");
        assertThat(extracted.masked()).isTrue();
        assertThat(extracted.hasName()).isTrue();
    }

    @Test
    void extractsK2ConnectSenderFields() throws Exception {
        var root = mapper.readTree("""
                {"topic":"buygoods_transaction_received","event":{"resource":{
                  "sender_phone_number":"+2547XXXXX874","first_name":"JANE","last_name":"WAMBUI"
                }}}
                """);
        var extracted = KopokopoPayerPayload.extract(root);
        assertThat(extracted.firstName()).isEqualTo("JANE");
        assertThat(extracted.lastName()).isEqualTo("WAMBUI");
        assertThat(extracted.masked()).isTrue();
    }
}
