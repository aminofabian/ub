package zelisline.ub.platform.installs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.desktop.license.DesktopLicenseIssue;
import zelisline.ub.desktop.license.DesktopLicenseIssueRepository;

/** Super Admin install list + per-machine license history. */
class SuperAdminDesktopInstallsControllerTest {

    private static final String INSTALL = "install-1";
    private static final String MACHINE = "a".repeat(64);

    private final DesktopInstallRepository installRepository = mock(DesktopInstallRepository.class);
    private final DesktopInstallService installService = mock(DesktopInstallService.class);
    private final DesktopLicenseIssueRepository issueRepository = mock(DesktopLicenseIssueRepository.class);

    private SuperAdminDesktopInstallsController controller() {
        return new SuperAdminDesktopInstallsController(installRepository, installService, issueRepository);
    }

    private static DesktopInstall install() {
        DesktopInstall row = new DesktopInstall();
        row.setInstallId(INSTALL);
        row.setMachineFingerprint(MACHINE);
        row.setBusinessName("Test Shop");
        row.setFirstSeenAt(Instant.now().minus(2, ChronoUnit.DAYS));
        row.setLastSeenAt(Instant.now());
        return row;
    }

    @Test
    void listMapsInstallsNewestFirst() {
        when(installRepository.findAllByOrderByLastSeenAtDesc(any(Pageable.class)))
            .thenReturn(List.of(install()));

        List<SuperAdminDesktopInstallsController.InstallRow> rows = controller().list(50);

        assertEquals(1, rows.size());
        assertEquals(INSTALL, rows.get(0).installId());
        assertEquals(MACHINE, rows.get(0).machineId());
    }

    @Test
    void issuesReturnsHistoryForTheMachine() {
        when(installRepository.findById(INSTALL)).thenReturn(Optional.of(install()));
        DesktopLicenseIssue issue = new DesktopLicenseIssue();
        issue.setId("lic-1");
        issue.setPlan("growth");
        issue.setIssuedAt(Instant.now());
        issue.setRecipientEmail("owner@shop.co.ke");
        issue.setEmailSent(true);
        issue.setCreatedAt(Instant.now());
        when(issueRepository.findAllByMachineFingerprintOrderByCreatedAtDesc(eq(MACHINE), any(Pageable.class)))
            .thenReturn(List.of(issue));

        List<SuperAdminDesktopInstallsController.InstallIssueRow> rows = controller().issues(INSTALL, 50);

        assertEquals(1, rows.size());
        assertEquals("lic-1", rows.get(0).id());
        assertEquals("growth", rows.get(0).plan());
    }

    @Test
    void issuesForUnknownInstallIs404() {
        when(installRepository.findById("missing")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(
            ResponseStatusException.class,
            () -> controller().issues("missing", 50));
        assertEquals(404, ex.getStatusCode().value());
    }
}
