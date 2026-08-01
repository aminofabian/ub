package zelisline.ub.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Regression: a fresh shop that only imported a catalogue (never posted a journal,
 * never created an expense schedule) can hit "Missing ledger account 1200" on its
 * first stock edit, because journal lines resolve ledger accounts *before*
 * {@code LedgerPostingService.post} bootstraps the standard accounts.
 *
 * <p>{@code LedgerAccountResolver.resolve} must self-heal by bootstrapping the
 * standard accounts on first miss (see the lazy-bootstrap fallback).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class LedgerAccountResolverIT {

    private static final String TENANT = "cccccccc-cccc-cccc-cccc-cccccccccc09";

    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;
    @Autowired
    private LedgerAccountResolver ledgerAccountResolver;

    @BeforeEach
    void seed() {
        ledgerAccountRepository.deleteAll();
        businessRepository.deleteAll();

        // Deliberately NO ledgerBootstrapService.ensureStandardAccounts(...) here —
        // the shop was created before ledger seeding existed and only imported a catalogue.
        Business b = new Business();
        b.setId(TENANT);
        b.setName("Fresh Catalogue Shop");
        b.setSlug("fresh-catalogue-shop");
        b.setTimezone("Africa/Nairobi");
        businessRepository.save(b);
    }

    @Test
    void resolve_inventoryCode_onUnbootstrappedBusiness_bootstrapsAndReturnsAccount() {
        String inventoryId = ledgerAccountResolver.resolveId(TENANT, LedgerAccountCodes.INVENTORY);

        assertThat(inventoryId).isNotBlank();
        assertThat(ledgerAccountRepository
                .findByBusinessIdAndCode(TENANT, LedgerAccountCodes.INVENTORY))
                .isPresent()
                .get()
                .extracting(zelisline.ub.finance.domain.LedgerAccount::getAccountType)
                .isEqualTo("asset");
        // Bootstrap is not a one-shot for one code: the full standard chart exists.
        assertThat(ledgerAccountRepository
                .findByBusinessIdAndCode(TENANT, LedgerAccountCodes.SALES_REVENUE))
                .isPresent();
        assertThat(ledgerAccountRepository
                .findByBusinessIdAndCode(TENANT, LedgerAccountCodes.INVENTORY_SHRINKAGE))
                .isPresent();
    }

    @Test
    void resolve_missingUnknownCode_stillThrows() {
        try {
            ledgerAccountResolver.resolveId(TENANT, "9999");
            org.assertj.core.api.Assertions.fail("Expected a missing-account failure");
        } catch (org.springframework.web.server.ResponseStatusException ex) {
            assertThat(ex.getStatusCode().value()).isEqualTo(400);
            assertThat(ex.getReason()).contains("Missing ledger account 9999");
        }
    }
}
