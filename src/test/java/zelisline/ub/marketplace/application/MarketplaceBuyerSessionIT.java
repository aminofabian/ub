package zelisline.ub.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.marketplace.api.dto.BuyerSessionResponse;
import zelisline.ub.marketplace.domain.MarketplaceBuyerSession;
import zelisline.ub.marketplace.repository.MarketplaceBuyerSessionRepository;
import zelisline.ub.storefront.application.ShopperIdentifyService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/** ADR 0043: a marketplace buyer is a verified phone number and the shops it is staff of. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class MarketplaceBuyerSessionIT {

    private static final String PHONE = "254700111222";

    @Autowired
    private MarketplaceBuyerSessionService buyerSessionService;

    @Autowired
    private MarketplaceBuyerSessionRepository sessionRepository;

    @Autowired
    private BusinessRepository businessRepository;

    /** The code itself is the existing door's job; here it has already been checked. */
    @MockitoBean
    private ShopperIdentifyService shopperIdentifyService;

    @MockitoBean
    private UserRepository userRepository;

    private String myShop;
    private String anotherShop;

    @BeforeEach
    void seed() {
        sessionRepository.deleteAll();
        myShop = saveShop("Njeri Mini Mart");
        anotherShop = saveShop("Somebody Else's Shop");
        when(shopperIdentifyService.consumeVerifiedPhone(anyString(), eq("good-token"))).thenReturn(PHONE);
        when(shopperIdentifyService.consumeVerifiedPhone(anyString(), eq("bad-token")))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired code"));
        staffOf(myShop);
    }

    @Test
    void aVerifiedPhoneOpensASessionForTheShopsItIsStaffOf() {
        BuyerSessionResponse opened = buyerSessionService.open("0700 111 222", "good-token");

        assertThat(opened.token()).isNotBlank();
        assertThat(opened.expiresAt()).isAfter(Instant.now());
        assertThat(opened.shops()).extracting(BuyerSessionResponse.Shop::businessId).containsExactly(myShop);
        // Only a hash of the token is kept.
        assertThat(sessionRepository.findAll()).hasSize(1);
        assertThat(sessionRepository.findAll().get(0).getTokenHash()).isNotEqualTo(opened.token());

        BuyerSessionResponse again = buyerSessionService.current(opened.token());
        assertThat(again.token()).isNull();
        assertThat(again.shops()).hasSize(1);

        MarketplaceBuyerSessionService.Buyer buyer = buyerSessionService.requireBuyerFor(opened.token(), myShop);
        assertThat(buyer.businessName()).isEqualTo("Njeri Mini Mart");
        assertThat(buyer.phone()).isEqualTo(PHONE);
    }

    @Test
    void aCodeThatWasNotVerifiedOpensNothing() {
        assertStatus(() -> buyerSessionService.open("0700111222", "bad-token"), HttpStatus.BAD_REQUEST);
        assertThat(sessionRepository.count()).isZero();
    }

    @Test
    void aSessionCannotOrderForAShopItsPhoneIsNotStaffOf() {
        String token = buyerSessionService.open("0700111222", "good-token").token();

        assertStatus(() -> buyerSessionService.requireBuyerFor(token, anotherShop), HttpStatus.FORBIDDEN);
        assertStatus(() -> buyerSessionService.requireBuyerFor(token, UUID.randomUUID().toString()), HttpStatus.FORBIDDEN);
    }

    @Test
    void someoneRemovedFromTheShopLosesItAtOnce() {
        String token = buyerSessionService.open("0700111222", "good-token").token();
        when(userRepository.findAllActiveByPhoneIn(anyCollection())).thenReturn(List.of());

        assertThat(buyerSessionService.current(token).shops()).isEmpty();
        assertStatus(() -> buyerSessionService.requireBuyerFor(token, myShop), HttpStatus.FORBIDDEN);
    }

    @Test
    void aMissingWrongExpiredOrClosedSessionIsRefused() {
        assertStatus(() -> buyerSessionService.current(null), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> buyerSessionService.current("not-a-token"), HttpStatus.UNAUTHORIZED);

        String expired = buyerSessionService.open("0700111222", "good-token").token();
        MarketplaceBuyerSession row = sessionRepository.findAll().get(0);
        row.setExpiresAt(Instant.now().minusSeconds(60));
        sessionRepository.save(row);
        assertStatus(() -> buyerSessionService.current(expired), HttpStatus.UNAUTHORIZED);

        String closed = buyerSessionService.open("0700111222", "good-token").token();
        buyerSessionService.close(closed);
        assertStatus(() -> buyerSessionService.requireBuyerFor(closed, myShop), HttpStatus.UNAUTHORIZED);
    }

    private String saveShop(String name) {
        Business shop = new Business();
        shop.setId(UUID.randomUUID().toString());
        shop.setName(name);
        shop.setSlug("buyer-" + UUID.randomUUID().toString().substring(0, 8));
        shop.setSettings("{}");
        return businessRepository.save(shop).getId();
    }

    private void staffOf(String businessId) {
        User staff = new User();
        staff.setBusinessId(businessId);
        staff.setPhone(PHONE);
        when(userRepository.findAllActiveByPhoneIn(anyCollection())).thenReturn(List.of(staff));
    }

    private static void assertStatus(Runnable call, HttpStatus expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(expected));
    }
}
