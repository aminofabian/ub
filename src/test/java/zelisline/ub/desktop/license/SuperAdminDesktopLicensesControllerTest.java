package zelisline.ub.desktop.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import zelisline.ub.desktop.license.SuperAdminDesktopLicensesController.IssueRecord;
import zelisline.ub.desktop.license.SuperAdminDesktopLicensesController.IssueRequest;
import zelisline.ub.desktop.license.SuperAdminDesktopLicensesController.IssueResponse;
import zelisline.ub.identity.application.NotificationService;

/**
 * Unit coverage for cloud-side license issuance (Super Admin → Desktop licenses):
 * signing matches the till's verifier, expiry resolution, and the email path.
 */
class SuperAdminDesktopLicensesControllerTest {

    private static final String BUSINESS = "Test Shop";
    private static final String FINGERPRINT = "a".repeat(64);

    private final KeyPair keys = LicenseService.generateKeyPair();
    private final String privateKey = LicenseService.encodePrivateKey(keys.getPrivate());
    private final String publicKey = LicenseService.encodePublicKey(keys.getPublic());

    private final NotificationService mail = mock(NotificationService.class);
    private final DesktopLicenseIssueRepository repo = mock(DesktopLicenseIssueRepository.class);

    private SuperAdminDesktopLicensesController controller() {
        when(repo.save(any(DesktopLicenseIssue.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        return new SuperAdminDesktopLicensesController(
            new DesktopLicenseIssuer(privateKey), mail, repo, null);
    }

    private LicenseService verifier() {
        return new LicenseService(publicKey);
    }

    @Test
    void issuedTokenVerifiesWithTheTillPublicKey() {
        LicenseService till = verifier();
        DesktopLicenseIssuer.IssuedLicense issued =
            new DesktopLicenseIssuer(privateKey).issue(BUSINESS, "shop", null, FINGERPRINT);

        LicensePayload payload = till.decodeAndVerify(issued.token());
        assertNotNull(payload, "token must verify against the matching public key");
        assertEquals(BUSINESS, payload.businessName());
        assertEquals("shop", payload.plan());
        assertNull(payload.expiresAt(), "perpetual license carries no expiry");
    }

    @Test
    void issueResolvesDaysAndDefaultsPlan() {
        IssueResponse response = controller().issue(
            new IssueRequest(BUSINESS, "", 365, null, null, FINGERPRINT, null));

        assertNotNull(response.token());
        assertEquals("shop", response.plan(), "blank plan defaults to shop");
        assertNotNull(response.expiresAt());
        long days = ChronoUnit.DAYS.between(Instant.now(), response.expiresAt());
        assertTrue(days >= 364 && days <= 366, "expiry ~365 days out, got " + days);

        LicensePayload payload = verifier().decodeAndVerify(response.token());
        assertNotNull(payload);
        assertEquals(BUSINESS, payload.businessName());
    }

    @Test
    void perpetualHasNoExpiry() {
        IssueResponse response = controller().issue(
            new IssueRequest(BUSINESS, "lan", null, null, true, FINGERPRINT, null));
        assertNull(response.expiresAt());
        assertFalse(response.emailSent());
    }

    @Test
    void missingValidityIsRejected() {
        assertThrows(
            ResponseStatusException.class,
            () -> controller().issue(new IssueRequest(BUSINESS, "shop", null, null, null, FINGERPRINT, null))
        );
    }

    @Test
    void invalidExpiryIsRejected() {
        assertThrows(
            ResponseStatusException.class,
            () -> controller().issue(
                new IssueRequest(BUSINESS, "shop", null, "not-an-instant", null, FINGERPRINT, null))
        );
    }

    @Test
    void issueAndEmailSendsToken() {
        IssueResponse response = controller().issueAndEmail(
            new IssueRequest(BUSINESS, "shop", 30, null, null, FINGERPRINT, "owner@shop.co.ke"));

        assertTrue(response.emailSent());
        assertEquals("owner@shop.co.ke", response.emailedTo());
        verify(mail).sendNotificationEmail(
            org.mockito.ArgumentMatchers.eq("owner@shop.co.ke"),
            contains("Kiosk Desktop license"),
            contains(response.token()),
            contains(response.token())
        );
    }

    @Test
    void issueAndEmailWithoutAddressIsRejected() {
        assertThrows(
            ResponseStatusException.class,
            () -> controller().issueAndEmail(
                new IssueRequest(BUSINESS, "shop", 30, null, null, FINGERPRINT, null))
        );
    }

    @Test
    void issuePersistsHistoryRow() {
        IssueResponse response = controller().issue(
            new IssueRequest(BUSINESS, "shop", 30, null, null, FINGERPRINT, "owner@shop.co.ke"));

        assertNotNull(response.id());
        assertNotNull(response.createdAt());
        verify(repo).save(any(DesktopLicenseIssue.class));
    }

    @Test
    void resendEmailsStoredToken() {
        DesktopLicenseIssue row = new DesktopLicenseIssue();
        row.setId("lic-1");
        row.setBusinessName(BUSINESS);
        row.setPlan("shop");
        row.setIssuedAt(Instant.now());
        row.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        row.setRecipientEmail("owner@shop.co.ke");
        row.setToken("stored-token");
        when(repo.findById("lic-1")).thenReturn(Optional.of(row));

        IssueRecord record = controller().resend("lic-1");

        assertEquals("lic-1", record.id());
        assertTrue(record.emailSent());
        verify(mail).sendNotificationEmail(
            org.mockito.ArgumentMatchers.eq("owner@shop.co.ke"),
            contains("Kiosk Desktop license"),
            contains("stored-token"),
            contains("stored-token")
        );
    }

    @Test
    void resendWithoutEmailIsRejected() {
        DesktopLicenseIssue row = new DesktopLicenseIssue();
        row.setId("lic-2");
        row.setBusinessName(BUSINESS);
        row.setToken("x");
        when(repo.findById("lic-2")).thenReturn(Optional.of(row));

        assertThrows(
            ResponseStatusException.class,
            () -> controller().resend("lic-2")
        );
    }

    @Test
    void unconfiguredIssuerReturns503() {
        when(repo.save(any(DesktopLicenseIssue.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        SuperAdminDesktopLicensesController unconfigured =
            new SuperAdminDesktopLicensesController(new DesktopLicenseIssuer(""), mail, repo, null);

        ResponseStatusException ex = assertThrows(
            ResponseStatusException.class,
            () -> unconfigured.issue(new IssueRequest(BUSINESS, "shop", 30, null, null, FINGERPRINT, null))
        );
        assertEquals(503, ex.getStatusCode().value());
        assertFalse(new DesktopLicenseIssuer("").isConfigured());
    }

    @Test
    void fingerprintIsCarriedThrough() {
        IssueResponse response = controller().issue(
            new IssueRequest(BUSINESS, "shop", 30, null, null, "a".repeat(64), null));
        LicensePayload payload = verifier().decodeAndVerify(response.token());
        assertNotNull(payload);
        assertEquals("a".repeat(64), payload.machineFingerprint());
    }

    @Test
    void missingFingerprintIsRejected() {
        ResponseStatusException ex = assertThrows(
            ResponseStatusException.class,
            () -> controller().issue(
                new IssueRequest(BUSINESS, "shop", 30, null, null, "  ", null))
        );
        assertEquals(400, ex.getStatusCode().value(), "a license must be bound to a machine");
    }
}
