package zelisline.ub.storefront.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import zelisline.ub.storefront.WebOrderCodes;

class WebOrderCodesDebugTest {
    @Test
    void codeMatches() {
        String id = "550e8400-e29b-41d4-a716-446655440000";
        String code = WebOrderCodes.code(id);
        System.out.println("CODE=" + code);
        assertThat(WebOrderCodes.matches("65544000", id)).isTrue();
    }
}
