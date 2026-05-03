package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.CreditTxnTypes;
import zelisline.ub.credits.WalletTxnTypes;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.CreditTransaction;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.LoyaltyTransaction;
import zelisline.ub.credits.domain.WalletTransaction;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditTransactionRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.repository.LoyaltyTransactionRepository;
import zelisline.ub.credits.repository.WalletTransactionRepository;

@Service
@RequiredArgsConstructor
public class CreditCustomerStatementService {

    private static final int MONEY_SCALE = 2;

    private final CustomerRepository customerRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final LoyaltyTransactionRepository loyaltyTransactionRepository;

    public CreditStatement assemble(String businessId, String customerId) {
        Customer c = customerRepository.findByIdAndBusinessIdAndDeletedAtIsNull(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
        CreditAccount acc = creditAccountRepository.findByCustomerIdAndBusinessId(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Credit profile not found"));

        List<StatementLineDto> merged = new ArrayList<>();
        for (CreditTransaction t : creditTransactionRepository.findByCreditAccountIdOrderByCreatedAtAsc(acc.getId())) {
            StatementLineDto line = mapCreditTxn(t);
            merged.add(line);
        }
        for (WalletTransaction w : walletTransactionRepository.findByCreditAccountIdOrderByCreatedAtAsc(acc.getId())) {
            merged.add(mapWalletTxn(w));
        }
        for (LoyaltyTransaction l : loyaltyTransactionRepository.findByCreditAccountIdOrderByCreatedAtAsc(acc.getId())) {
            merged.add(mapLoyaltyTxn(l));
        }
        merged.sort(Comparator.comparing(StatementLineDto::at));
        return new CreditStatement(
                c.getId(),
                c.getName(),
                acc.getBalanceOwed().setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                acc.getWalletBalance().setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                acc.getLoyaltyPoints(),
                List.copyOf(merged));
    }

    private static StatementLineDto mapCreditTxn(CreditTransaction t) {
        BigDecimal amt = t.getAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal dr = CreditTxnTypes.DEBT.equals(t.getTxnType()) ? amt : BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal cr = CreditTxnTypes.DEBT.equals(t.getTxnType()) ? BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP) : amt;
        return new StatementLineDto(
                t.getCreatedAt(),
                "credit_" + t.getTxnType(),
                dr,
                cr,
                t.getTxnType());
    }

    private static StatementLineDto mapWalletTxn(WalletTransaction w) {
        BigDecimal amt = w.getAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal debit = debitWalletExposure(w.getTxnType(), amt);
        BigDecimal credit = creditWalletExposure(w.getTxnType(), amt);
        return new StatementLineDto(w.getCreatedAt(), "wallet_" + w.getTxnType(), debit, credit, w.getTxnType());
    }

    private static BigDecimal debitWalletExposure(String type, BigDecimal amt) {
        boolean d = WalletTxnTypes.DEBIT_SALE.equals(type) || WalletTxnTypes.REVERSAL_VOID_OVERPAY_CLAW.equals(type);
        return d ? amt : zeroMoney();
    }

    private static BigDecimal creditWalletExposure(String type, BigDecimal amt) {
        boolean c = WalletTxnTypes.CREDIT_OVERPAY_CHANGE.equals(type)
                || WalletTxnTypes.CREDIT_COUNTER_TOPUP.equals(type)
                || WalletTxnTypes.CREDIT_MPESA_STK.equals(type)
                || WalletTxnTypes.CREDIT_REFUND.equals(type)
                || WalletTxnTypes.REVERSAL_VOID_SPEND_RESTORE.equals(type);
        return c ? amt : zeroMoney();
    }

    private static BigDecimal zeroMoney() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static StatementLineDto mapLoyaltyTxn(LoyaltyTransaction l) {
        return new StatementLineDto(
                l.getCreatedAt(),
                "loyalty_" + l.getTxnType(),
                zeroMoney(),
                zeroMoney(),
                l.getTxnType() + ":" + l.getPoints());
    }

    public record StatementLineDto(Instant at, String kind, BigDecimal debit, BigDecimal credit, String memo) {
    }

    public record CreditStatement(
            String customerId,
            String customerName,
            BigDecimal balanceOwed,
            BigDecimal walletBalance,
            int loyaltyPoints,
            List<StatementLineDto> lines
    ) {
    }
}
