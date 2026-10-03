package zelisline.ub.platform.installs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import zelisline.ub.billing.domain.SubscriptionBillingStatus;
import zelisline.ub.desktop.license.DesktopLicenseIssue;
import zelisline.ub.desktop.license.DesktopLicenseIssuanceService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Auto-activation policy for the desktop install registry: only a connected
 * shop on a live paid plan, with an owner email and no existing machine token,
 * is activated without a human.
 */
class DesktopInstallServiceTest {

    private static final String INSTALL = "install-1";
    private static final String MACHINE = "a".repeat(64);
    private static final String BIZ = "biz-1";

    private final DesktopInstallRepository installRepository = mock(DesktopInstallRepository.class);
    private final DesktopLicenseIssuanceService issuance = mock(DesktopLicenseIssuanceService.class);
    private final BusinessRepository businessRepository = mock(BusinessRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private DesktopInstallService service() {
        when(installRepository.save(any(DesktopInstall.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        return new DesktopInstallService(installRepository, issuance, businessRepository, userRepository);
    }

    private DesktopInstallService.Registration registration(String cloudBusinessId) {
        return new DesktopInstallService.Registration(
            INSTALL, MACHINE, "Test Shop", "owner@shop.co.ke",
            "0.0.1", "macos", cloudBusinessId, "trial", "growth");
    }

    private static Business paidBusiness() {
        Business b = new Business();
        b.setId(BIZ);
        b.setName("Test Shop");
        b.setSubscriptionTier("growth");
        b.setSubscriptionBillingStatus(SubscriptionBillingStatus.ACTIVE);
        b.setCurrentPeriodEnd(Instant.now().plus(20, ChronoUnit.DAYS));
        return b;
    }

    private static User owner(String email) {
        User u = new User();
        u.setEmail(email);
        return u;
    }

    private static DesktopLicenseIssue issued() {
        DesktopLicenseIssue row = new DesktopLicenseIssue();
        row.setId("lic-1");
        row.setToken("token");
        row.setPlan("growth");
        row.setBusinessName("Test Shop");
        row.setIssuedAt(Instant.now());
        return row;
    }

    @Test
    void registersAndStampsFirstSeen() {
        when(installRepository.findById(INSTALL)).thenReturn(Optional.empty());

        DesktopInstall saved = service().register(registration(null), "203.0.113.4");

        assertNotNull(saved.getFirstSeenAt());
        assertNotNull(saved.getLastSeenAt());
        assertEquals(MACHINE, saved.getMachineFingerprint());
        assertEquals("203.0.113.4", saved.getLastIp());
        verify(issuance, never()).issue(anyString(), anyString(), anyInt(), any(), anyBoolean(), anyString(), any());
    }

    @Test
    void autoActivatesPaidConnectedShopWithOwnerEmail() {
        when(businessRepository.findByIdAndDeletedAtIsNull(BIZ)).thenReturn(Optional.of(paidBusiness()));
        when(userRepository.findActiveByRoleKeyOrderByCreatedAtAsc(BIZ, "owner"))
            .thenReturn(List.of(owner("owner@shop.co.ke")));
        when(issuance.hasActiveLicenseFor(MACHINE)).thenReturn(false);
        when(issuance.issue(eq("Test Shop"), eq("growth"), eq(365), any(), eq(false), eq(MACHINE), eq("owner@shop.co.ke")))
            .thenReturn(issued());

        DesktopInstall saved = service().register(registration(BIZ), "203.0.113.4");

        assertNotNull(saved.getAutoIssuedAt());
        assertEquals("lic-1", saved.getLastLicenseIssueId());
        assertEquals("active", saved.getLicenseState());
        verify(issuance).issue("Test Shop", "growth", 365, null, false, MACHINE, "owner@shop.co.ke");
    }

    @Test
    void doesNotAutoActivateLocalOnlyInstall() {
        DesktopInstall saved = service().register(registration(null), "203.0.113.4");

        assertNull(saved.getAutoIssuedAt());
        verify(issuance, never()).issue(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void doesNotAutoActivateWhenMachineAlreadyLicensed() {
        when(businessRepository.findByIdAndDeletedAtIsNull(BIZ)).thenReturn(Optional.of(paidBusiness()));
        when(issuance.hasActiveLicenseFor(MACHINE)).thenReturn(true);

        DesktopInstall saved = service().register(registration(BIZ), "203.0.113.4");

        assertNull(saved.getAutoIssuedAt());
        verify(issuance, never()).issue(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void doesNotAutoActivateUnpaidShop() {
        Business free = paidBusiness();
        free.setSubscriptionTier("free");
        when(businessRepository.findByIdAndDeletedAtIsNull(BIZ)).thenReturn(Optional.of(free));

        DesktopInstall saved = service().register(registration(BIZ), "203.0.113.4");

        assertNull(saved.getAutoIssuedAt());
        verify(issuance, never()).issue(any(), any(), any(), any(), any(), any(), any());
    }
}
