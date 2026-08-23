package zelisline.ub.storefront.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderRepository;

class ReceiptTokenServiceTest {

    private WebOrderRepository webOrderRepository;
    private ReceiptTokenService service;

    @BeforeEach
    void setUp() {
        webOrderRepository = Mockito.mock(WebOrderRepository.class);
        service = new ReceiptTokenService(webOrderRepository);
    }

    @Test
    void mintStoresHashAndExpiryAndReturnsRawToken() {
        WebOrder order = order();
        Instant before = Instant.now();

        String token = service.mint(order);

        assertThat(token).hasSize(24);
        assertThat(order.getReceiptTokenHash()).isEqualTo(TokenHasher.sha256Hex(token));
        assertThat(order.getReceiptTokenConsumedAt()).isNull();
        assertThat(order.getReceiptTokenExpiresAt())
                .isNotNull()
                .isAfter(before);
        Mockito.verify(webOrderRepository).save(order);
    }

    @Test
    void verifyConsumesMatchingTokenOnce() {
        WebOrder order = order();
        String token = service.mint(order);

        assertThat(service.verifyAndConsume(order, token)).isTrue();
        assertThat(order.getReceiptTokenConsumedAt()).isNotNull();

        // Single-use: a second redemption fails even with the same token.
        assertThat(service.verifyAndConsume(order, token)).isFalse();
    }

    @Test
    void verifyRejectsWrongExpiredAndEmptyTokens() {
        WebOrder order = order();
        String token = service.mint(order);

        assertThat(service.verifyAndConsume(order, token + "X")).isFalse();
        assertThat(service.verifyAndConsume(order, "")).isFalse();
        assertThat(service.verifyAndConsume(order, null)).isFalse();

        order.setReceiptTokenExpiresAt(Instant.now().minusSeconds(1));
        assertThat(service.verifyAndConsume(order, token)).isFalse();
    }

    @Test
    void verifyRejectsTokenWhenOrderHasNoHash() {
        WebOrder order = order();
        assertThat(service.verifyAndConsume(order, "SOMETOKEN")).isFalse();
    }

    private static WebOrder order() {
        WebOrder order = new WebOrder();
        order.setId("550e8400-e29b-41d4-a716-446655440000");
        order.setBusinessId("b1");
        return order;
    }
}
