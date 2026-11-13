package zelisline.ub.serviceops.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.WalletLedgerService;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerAccountResolver;
import zelisline.ub.finance.application.LedgerPostingPort;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.application.DrawerPaidOutService;

/**
 * Money a service business holds for a customer before the work is done: booking deposits
 * ({@code CUSTOMER_DEPOSITS}) and prepaid packages ({@code DEFERRED_SERVICE_REVENUE}) — ADR 0015.
 *
 * <p>Taking the money debits cash or M-Pesa clearing and credits the liability. Using it moves
 * the amount into the customer's wallet, and the sale is then paid from the wallet with the
 * ordinary wallet tender; so a void or refund of that sale needs nothing new. Every posting goes
 * through {@link LedgerPostingPort}.</p>
 */
@Service
@RequiredArgsConstructor
public class HeldMoneyService {

    public static final String SOURCE_DEPOSIT_TAKEN = "service_deposit";
    public static final String SOURCE_DEPOSIT_RELEASED = "service_deposit_release";
    public static final String SOURCE_DEPOSIT_FORFEITED = "service_deposit_forfeit";
    public static final String SOURCE_PACKAGE_SOLD = "service_package";
    public static final String SOURCE_PACKAGE_SESSION = "service_package_session";

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");

    private final LedgerPostingPort ledgerPostingPort;
    private final LedgerAccountResolver ledgerAccountResolver;
    private final WalletLedgerService walletLedgerService;
    private final DrawerPaidOutService drawerPaidOutService;

    /**
     * Receives money into a liability account. Cash goes into the open shift's drawer.
     *
     * @param method {@code cash} or {@code mpesa_manual}
     * @return the journal entry id
     */
    @Transactional
    public String receive(
            String businessId, String branchId, String liabilityCode, String sourceType, String sourceId,
            BigDecimal amount, String method, String memo, String userId, String tillDeviceKey
    ) {
        BigDecimal money = requireAmount(amount);
        String debit;
        if (SalesConstants.PAYMENT_METHOD_CASH.equals(method)) {
            drawerPaidOutService.payIn(businessId, branchId, tillDeviceKey, money, sourceId, userId);
            debit = LedgerAccountCodes.OPERATING_CASH;
        } else if (SalesConstants.PAYMENT_METHOD_MPESA_MANUAL.equals(method)) {
            debit = LedgerAccountCodes.MPESA_CLEARING;
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pay by cash or M-Pesa");
        }
        return post(businessId, branchId, sourceType, sourceId, memo, debit, liabilityCode, money);
    }

    /** Moves held money into the customer's wallet, where the sale (or the customer, later) spends it. */
    @Transactional
    public void releaseToWallet(
            String businessId, String branchId, String liabilityCode, String sourceType, String sourceId,
            String customerId, BigDecimal amount, String walletTxnType, String memo
    ) {
        BigDecimal money = requireAmount(amount);
        walletLedgerService.creditWalletFromHeldLiability(businessId, customerId, money, walletTxnType);
        post(businessId, branchId, sourceType, sourceId, memo,
                liabilityCode, LedgerAccountCodes.CUSTOMER_WALLET_LIABILITY, money);
    }

    /** The business keeps held money it no longer owes back (a deposit after a no-show). */
    @Transactional
    public void forfeit(
            String businessId, String branchId, String liabilityCode, String sourceType, String sourceId,
            BigDecimal amount, String memo
    ) {
        post(businessId, branchId, sourceType, sourceId, memo,
                liabilityCode, LedgerAccountCodes.SERVICE_REVENUE, requireAmount(amount));
    }

    public static BigDecimal requireAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an amount above zero");
        }
        BigDecimal money = amount.setScale(2, RoundingMode.HALF_UP);
        if (money.compareTo(MAX_AMOUNT) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That amount is too large");
        }
        return money;
    }

    private String post(
            String businessId, String branchId, String sourceType, String sourceId, String memo,
            String debitCode, String creditCode, BigDecimal amount
    ) {
        JournalEntry journal = new JournalEntry();
        journal.setBusinessId(businessId);
        journal.setBranchId(branchId == null || branchId.isBlank() ? null : branchId);
        journal.setEntryDate(LocalDate.now(ZoneOffset.UTC));
        journal.setSourceType(sourceType);
        journal.setSourceId(sourceId);
        journal.setMemo(memo);
        journal.debit(ledgerAccountResolver.resolveId(businessId, debitCode), amount);
        journal.credit(ledgerAccountResolver.resolveId(businessId, creditCode), amount);
        return ledgerPostingPort.post(journal);
    }
}
