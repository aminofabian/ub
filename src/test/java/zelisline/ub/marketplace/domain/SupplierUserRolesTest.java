package zelisline.ub.marketplace.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SupplierUserRolesTest {

    @Test
    void adminHasMoneyAndTeam() {
        assertTrue(SupplierUserRoles.permissionsFor("admin").contains(SupplierUserRoles.PERM_MONEY_READ));
        assertTrue(SupplierUserRoles.permissionsFor("owner").contains(SupplierUserRoles.PERM_TEAM_MANAGE));
    }

    @Test
    void staffHasOpsButNotMoney() {
        var perms = SupplierUserRoles.permissionsFor("staff");
        assertTrue(perms.contains(SupplierUserRoles.PERM_ORDERS_SHIP));
        assertTrue(perms.contains(SupplierUserRoles.PERM_CATALOG_WRITE));
        assertFalse(perms.contains(SupplierUserRoles.PERM_MONEY_READ));
        assertFalse(perms.contains(SupplierUserRoles.PERM_TEAM_MANAGE));
    }

    @Test
    void normalizeAcceptsOwnerAlias() {
        assertEquals(SupplierUserRoles.ADMIN, SupplierUserRoles.normalize("owner"));
        assertEquals(SupplierUserRoles.STAFF, SupplierUserRoles.normalize("STAFF"));
        assertThrows(IllegalArgumentException.class, () -> SupplierUserRoles.normalize("cashier"));
    }
}
