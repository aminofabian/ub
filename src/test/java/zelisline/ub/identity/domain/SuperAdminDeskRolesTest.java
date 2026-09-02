package zelisline.ub.identity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

class SuperAdminDeskRolesTest {

    @Test
    void blankRoleDefaultsToOwner() {
        assertEquals(SuperAdminDeskRoles.OWNER, SuperAdminDeskRoles.normalize(null));
        assertEquals(SuperAdminDeskRoles.OWNER, SuperAdminDeskRoles.normalize(""));
    }

    @Test
    void unknownRoleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SuperAdminDeskRoles.normalize("cashier"));
    }

    @Test
    void agentCannotSeeFullConsoleOrManageStaff() {
        assertFalse(SuperAdminDeskRoles.canSeeFullConsole(SuperAdminDeskRoles.AGENT));
        assertFalse(SuperAdminDeskRoles.canManageStaff(SuperAdminDeskRoles.AGENT));
        assertTrue(SuperAdminDeskRoles.permissionsFor(SuperAdminDeskRoles.AGENT)
                .contains(SuperAdminDeskRoles.PERM_SERVING_ACCESS));
        assertTrue(SuperAdminDeskRoles.permissionsFor(SuperAdminDeskRoles.AGENT)
                .contains(SuperAdminDeskRoles.PERM_INBOX_ACCESS));
        assertFalse(SuperAdminDeskRoles.permissionsFor(SuperAdminDeskRoles.AGENT)
                .contains(SuperAdminDeskRoles.PERM_CONSOLE_FULL));
    }

    @Test
    void leadHasConsoleAndStaff() {
        assertTrue(SuperAdminDeskRoles.canSeeFullConsole(SuperAdminDeskRoles.LEAD));
        assertTrue(SuperAdminDeskRoles.canManageStaff(SuperAdminDeskRoles.LEAD));
        assertTrue(SuperAdminDeskRoles.authoritiesFor(SuperAdminDeskRoles.LEAD).stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("PERM_sa.staff.manage"::equals));
        assertTrue(SuperAdminDeskRoles.authoritiesFor(SuperAdminDeskRoles.LEAD).stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("PERM_sa.inbox.access"::equals));
    }
}
