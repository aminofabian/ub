package zelisline.ub.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.domain.LedgerAccount;
import zelisline.ub.finance.repository.LedgerAccountRepository;

/** The service-business accounts (ADR 0014) are bootstrapped, including for businesses that already exist. */
class LedgerBootstrapServiceAccountsTest {

    private static final String BUSINESS = "biz-1";

    private final LedgerAccountRepository repository = mock(LedgerAccountRepository.class);
    private final LedgerBootstrapService service = new LedgerBootstrapService(repository);

    @Test
    void aNewBusinessGetsTheServiceAccountsWithTheRightTypes() {
        when(repository.existsByBusinessIdAndCode(eq(BUSINESS), anyString())).thenReturn(false);

        service.ensureStandardAccounts(BUSINESS);

        Map<String, String> typeByCode = saved().stream()
                .collect(Collectors.toMap(LedgerAccount::getCode, LedgerAccount::getAccountType));
        assertThat(typeByCode)
                .containsEntry(LedgerAccountCodes.SERVICE_REVENUE, "revenue")
                .containsEntry(LedgerAccountCodes.COMMISSION_EXPENSE, "expense")
                .containsEntry(LedgerAccountCodes.COMMISSIONS_PAYABLE, "liability")
                .containsEntry(LedgerAccountCodes.TIPS_PAYABLE, "liability")
                // Money held for customers: booking deposits and prepaid packages (ADR 0015).
                .containsEntry(LedgerAccountCodes.CUSTOMER_DEPOSITS, "liability")
                .containsEntry(LedgerAccountCodes.DEFERRED_SERVICE_REVENUE, "liability");
        // No code is created twice in one pass.
        assertThat(saved()).extracting(LedgerAccount::getCode).doesNotHaveDuplicates();
        assertThat(saved()).allMatch(a -> BUSINESS.equals(a.getBusinessId()));
    }

    @Test
    void aBusinessBootstrappedBeforeThisChangeGainsOnlyTheNewAccounts() {
        Set<String> added = Set.of(
                LedgerAccountCodes.SERVICE_REVENUE,
                LedgerAccountCodes.COMMISSION_EXPENSE,
                LedgerAccountCodes.COMMISSIONS_PAYABLE,
                LedgerAccountCodes.TIPS_PAYABLE,
                LedgerAccountCodes.CUSTOMER_DEPOSITS,
                LedgerAccountCodes.DEFERRED_SERVICE_REVENUE);
        when(repository.existsByBusinessIdAndCode(eq(BUSINESS), anyString()))
                .thenAnswer(inv -> !added.contains(inv.<String>getArgument(1)));

        service.ensureStandardAccounts(BUSINESS);

        assertThat(saved()).extracting(LedgerAccount::getCode).containsExactlyInAnyOrderElementsOf(added);
    }

    @Test
    void runningAgainCreatesNothing() {
        when(repository.existsByBusinessIdAndCode(eq(BUSINESS), anyString())).thenReturn(true);
        service.ensureStandardAccounts(BUSINESS);
        verify(repository, never()).save(any());
    }

    @Test
    void newCodesDoNotCollideWithExistingOnes() throws Exception {
        Set<String> seen = new HashSet<>();
        for (var field : LedgerAccountCodes.class.getFields()) {
            if (field.getType() == String.class) {
                String code = (String) field.get(null);
                assertThat(seen.add(code)).as("duplicate ledger code %s (%s)", code, field.getName()).isTrue();
            }
        }
        assertThat(LedgerAccountCodes.isSalesOrServiceRevenue(LedgerAccountCodes.SALES_REVENUE)).isTrue();
        assertThat(LedgerAccountCodes.isSalesOrServiceRevenue(LedgerAccountCodes.SERVICE_REVENUE)).isTrue();
        assertThat(LedgerAccountCodes.isSalesOrServiceRevenue(LedgerAccountCodes.TIPS_PAYABLE)).isFalse();
        assertThat(LedgerAccountCodes.isSalesOrServiceRevenue(null)).isFalse();
    }

    private List<LedgerAccount> saved() {
        ArgumentCaptor<LedgerAccount> captor = ArgumentCaptor.forClass(LedgerAccount.class);
        verify(repository, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }
}
