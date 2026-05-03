package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.CreditTxnTypes;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.CreditTransaction;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditTransactionRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SalePayment;

@Service
@RequiredArgsConstructor
public class CreditSaleDebtService {

    private static final int MONEY_SCALE = 2;

    private final CustomerRepository customerRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final CreditTransactionRepository creditTransactionRepository;

    @Transactional
    public void applyDebtForNewSale(String businessId, String saleId, String customerId, BigDecimal creditTenderTotal) {
        if (creditTenderTotal == null || creditTenderTotal.signum() <= 0) {
            return;
        }
        CreditAccount acc = loadAccountForUpdate(customerId, businessId);
        BigDecimal scaled = creditTenderTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal next = acc.getBalanceOwed().add(scaled).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        assertWithinLimit(acc, next);
        acc.setBalanceOwed(next);
        acc.setLastActivityAt(Instant.now());
        creditAccountRepository.save(acc);
        insertTxn(businessId, acc.getId(), saleId, CreditTxnTypes.DEBT, scaled);
    }

    @Transactional
    public void reverseDebtForVoidedSale(String businessId, Sale sale, List<SalePayment> payments) {
        BigDecimal credit = sumCustomerCredit(payments);
        if (credit.signum() <= 0) {
            return;
        }
        String customerId = sale.getCustomerId();
        if (customerId == null || customerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sale uses tab tender but has no customer");
        }
        CreditAccount acc = loadAccountForUpdate(customerId, businessId);
        BigDecimal next = acc.getBalanceOwed().subtract(credit).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (next.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Void would make customer debt negative");
        }
        acc.setBalanceOwed(next);
        acc.setLastActivityAt(Instant.now());
        creditAccountRepository.save(acc);
        insertTxn(businessId, acc.getId(), sale.getId(), CreditTxnTypes.ADJUSTMENT, credit);
    }

    @Transactional
    public void reduceDebtForCreditRefund(String businessId, String saleId, String customerId, BigDecimal creditRefund) {
        if (creditRefund == null || creditRefund.signum() <= 0) {
            return;
        }
        if (customerId == null || customerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer required for tab refund");
        }
        CreditAccount acc = loadAccountForUpdate(customerId, businessId);
        BigDecimal scaled = creditRefund.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal next = acc.getBalanceOwed().subtract(scaled).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (next.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refund would make customer debt negative");
        }
        acc.setBalanceOwed(next);
        acc.setLastActivityAt(Instant.now());
        creditAccountRepository.save(acc);
        insertTxn(businessId, acc.getId(), saleId, CreditTxnTypes.ADJUSTMENT, scaled);
    }

    public void assertCustomerExists(String businessId, String customerId) {
        customerRepository.findByIdAndBusinessIdAndDeletedAtIsNull(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer not found"));
    }

    /** Reduces {@code balance_owed} after verified inbound tender (claims, gateways). */
    @Transactional
    public void applyInboundArPayment(String businessId, String creditAccountId, BigDecimal paymentAmount) {
        if (paymentAmount == null || paymentAmount.signum() <= 0) {
            return;
        }
        CreditAccount acc = creditAccountRepository.findByIdAndBusinessIdForUpdate(creditAccountId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Credit account not found"));
        BigDecimal scaled = paymentAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal next = acc.getBalanceOwed().subtract(scaled).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (next.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment exceeds open balance");
        }
        acc.setBalanceOwed(next);
        acc.setLastActivityAt(Instant.now());
        creditAccountRepository.save(acc);
        insertTxn(businessId, acc.getId(), null, CreditTxnTypes.PAYMENT, scaled);
    }

    private CreditAccount loadAccountForUpdate(String customerId, String businessId) {
        return creditAccountRepository.findByCustomerIdAndBusinessIdForUpdate(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Credit account not found"));
    }

    private static void assertWithinLimit(CreditAccount acc, BigDecimal nextBalance) {
        BigDecimal limit = acc.getCreditLimit();
        if (limit != null && nextBalance.compareTo(limit.setScale(MONEY_SCALE, RoundingMode.HALF_UP)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Exceeds customer credit limit");
        }
    }

    private void insertTxn(String businessId, String creditAccountId, String saleId, String type, BigDecimal amount) {
        CreditTransaction row = new CreditTransaction();
        row.setBusinessId(businessId);
        row.setCreditAccountId(creditAccountId);
        row.setSaleId(saleId);
        row.setTxnType(type);
        row.setAmount(amount);
        creditTransactionRepository.save(row);
    }

    private static BigDecimal sumCustomerCredit(List<SalePayment> payments) {
        BigDecimal s = BigDecimal.ZERO;
        for (SalePayment p : payments) {
            if (SalesConstants.PAYMENT_METHOD_CUSTOMER_CREDIT.equals(p.getMethod())) {
                s = s.add(p.getAmount());
            }
        }
        return s.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
