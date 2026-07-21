package zelisline.ub.messaging.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CustomerTabPaymentUrlTest {

    @Test
    void build_appendsLocalPhoneToOrigin() {
        assertEquals(
                "https://palmart.co.ke/0714282874",
                CustomerTabPaymentUrl.build("https://palmart.co.ke", "0714282874"));
        assertEquals(
                "https://palmart.co.ke/0714282874",
                CustomerTabPaymentUrl.build("https://palmart.co.ke/shop/account", "254714282874"));
        assertEquals(
                "https://shop.example.com/0712345678",
                CustomerTabPaymentUrl.build("https://shop.example.com/", "712345678"));
    }

    @Test
    void originOf_stripsPath() {
        assertEquals("https://palmart.co.ke", CustomerTabPaymentUrl.originOf("https://palmart.co.ke/shop/account"));
        assertEquals("https://palmart.co.ke", CustomerTabPaymentUrl.originOf("https://palmart.co.ke/"));
        assertEquals("https://palmart.co.ke", CustomerTabPaymentUrl.originOf("https://palmart.co.ke"));
    }
}
