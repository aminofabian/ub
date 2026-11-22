package zelisline.ub.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import zelisline.ub.identity.application.AuthService;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.tenancy.api.TenantRequestIds;

class SupplierShopSignInServiceTest {

    private static final String BUSINESS = "biz-1";
    private static final String EMAIL = "amina@example.com";

    private final MarketplaceSupplierRepository pages = mock(MarketplaceSupplierRepository.class);
    private final SupplierUserRepository portalUsers = mock(SupplierUserRepository.class);
    private final UserRepository shopUsers = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuthService auth = mock(AuthService.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final SupplierPortalSessionService portalSessions = mock(SupplierPortalSessionService.class);

    private final SupplierShopSignInService service = new SupplierShopSignInService(
            pages, portalUsers, shopUsers, roles, encoder, auth, identity, portalSessions);

    private static MockHttpServletRequest shopRequest() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        TenantRequestIds.bindBusinessId(http, BUSINESS);
        return http;
    }

    private SupplierUser portalUser() {
        MarketplaceSupplier page = new MarketplaceSupplier();
        page.setId("page-1");
        page.setBusinessId(BUSINESS);
        when(pages.findFirstByBusinessId(BUSINESS)).thenReturn(Optional.of(page));
        SupplierUser user = new SupplierUser();
        user.setMarketplaceSupplierId("page-1");
        user.setEmail(EMAIL);
        user.setPasswordHash("hash");
        user.setBusinessUserId("shop-user-1");
        when(portalUsers.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void doesNothingAwayFromAShop() {
        assertThat(service.signInToShop(new MockHttpServletRequest(), EMAIL, "secret")).isEmpty();
        verifyNoInteractions(pages, portalUsers, shopUsers, auth, identity);
    }

    @Test
    void aWrongGuessIsCountedOnItsOwnCounterAndIssuesNoSession() {
        SupplierUser user = portalUser();
        when(encoder.matches("wrong", "hash")).thenReturn(false);

        assertThat(service.signInToShop(shopRequest(), EMAIL, "wrong")).isEmpty();

        assertThat(user.getShopSigninFailures()).isEqualTo(1);
        // The portal's own counter and lock are left alone: guessing here cannot lock the portal.
        assertThat(user.getFailedAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        verify(auth, never()).issueSessionForUser(any(), any(), any());
    }

    @Test
    void theFifthWrongGuessLocksThisRouteForAWhile() {
        SupplierUser user = portalUser();
        user.setShopSigninFailures(SupplierShopSignInService.FREE_ATTEMPTS - 1);
        when(encoder.matches("wrong", "hash")).thenReturn(false);

        service.signInToShop(shopRequest(), EMAIL, "wrong");

        assertThat(user.getShopSigninLockedUntil()).isAfter(Instant.now());
    }

    @Test
    void whileLockedThePasswordIsNotEvenChecked() {
        SupplierUser user = portalUser();
        user.setShopSigninLockedUntil(Instant.now().plusSeconds(600));

        assertThat(service.signInToShop(shopRequest(), EMAIL, "secret")).isEmpty();

        verifyNoInteractions(encoder, auth);
    }

    @Test
    void theLockGrowsWithEachWrongGuessAndStopsAtADay() {
        assertThat(SupplierShopSignInService.lockFor(5)).isEqualTo(Duration.ofMinutes(15));
        assertThat(SupplierShopSignInService.lockFor(6)).isEqualTo(Duration.ofMinutes(30));
        assertThat(SupplierShopSignInService.lockFor(40)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void aPortalUserIsNeverJoinedToAnExistingShopAccountWithTheSameEmail() {
        SupplierUser user = portalUser();
        user.setBusinessUserId(null);
        when(encoder.matches("secret", "hash")).thenReturn(true);
        when(shopUsers.existsByBusinessIdAndEmailAndDeletedAtIsNull(BUSINESS, EMAIL)).thenReturn(true);

        assertThat(service.signInToShop(shopRequest(), EMAIL, "secret")).isEmpty();

        assertThat(user.getBusinessUserId()).isNull();
        verifyNoInteractions(identity, auth);
    }
}
