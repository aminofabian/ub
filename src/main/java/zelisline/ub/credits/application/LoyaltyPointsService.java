package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.LoyaltyTxnTypes;
import zelisline.ub.credits.domain.BusinessCreditSettings;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.LoyaltyTransaction;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.LoyaltyTransactionRepository;

@Service
@RequiredArgsConstructor
public class LoyaltyPointsService {

    private static final int MONEY_SCALE = 2;

    private final LoyaltyTransactionRepository loyaltyTransactionRepository;
    private final CreditAccountRepository creditAccountRepository;

    public void validateRedeemTender(
            String businessId,
            String customerId,
            BigDecimal basketTotal,
            BigDecimal redeemTenderKes,
            BusinessCreditSettings settings
    ) {
        BigDecimal redeem = nz(redeemTenderKes);
        if (redeem.signum() <= 0) {
            return;
        }
        requireCustomer(customerId);
        CreditAccount acc = loadAccount(customerId, businessId);
        int maxBps = settings.getLoyaltyMaxRedeemBps();
        BigDecimal ceiling = nz(basketTotal)
                .multiply(BigDecimal.valueOf(maxBps))
                .divide(BigDecimal.valueOf(10_000L), MONEY_SCALE, RoundingMode.HALF_UP);
        if (redeem.compareTo(ceiling) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loyalty redemption exceeds configured cap");
        }
        BigDecimal kp = kesPerPoint(settings);
        int ptsCost = redeem.divide(kp, 0, RoundingMode.UP).intValueExact();
        if (ptsCost <= 0 || acc.getLoyaltyPoints() < ptsCost) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient loyalty points");
        }
    }

    @Transactional
    public void applyAfterCompletedSale(
            String businessId,
            String customerId,
            String saleId,
            BigDecimal grandTotal,
            BigDecimal redeemTenderTotal,
            BusinessCreditSettings settings
    ) {
        if (blank(customerId)) {
            return;
        }
        CreditAccount acc =
                creditAccountRepository.findByCustomerIdAndBusinessIdForUpdate(customerId, businessId)
                        .orElseThrow(() ->
                                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Credit account not found"));
        redeemTenderTotal = nz(redeemTenderTotal);
        if (redeemTenderTotal.signum() > 0) {
            BigDecimal kp = kesPerPoint(settings);
            int spendPts = redeemTenderTotal.divide(kp, 0, RoundingMode.UP).intValueExact();
            acc.setLoyaltyPoints(acc.getLoyaltyPoints() - spendPts);
            acc.setLastActivityAt(Instant.now());
            loyaltyTransactionRepository.save(tx(businessId, acc.getId(), saleId, LoyaltyTxnTypes.REDEEM, spendPts));
        }
        int earnPts = calculateEarnPoints(grandTotal, settings);
        if (earnPts > 0) {
            acc.setLoyaltyPoints(acc.getLoyaltyPoints() + earnPts);
            acc.setLastActivityAt(Instant.now());
            loyaltyTransactionRepository.save(tx(businessId, acc.getId(), saleId, LoyaltyTxnTypes.EARN, earnPts));
        }
        creditAccountRepository.save(acc);
        if (acc.getLoyaltyPoints() < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Loyalty balance went negative");
        }
    }

    @Transactional
    public void reverseLoyaltyForVoidedSale(String businessId, String saleId, String customerId) {
        if (blank(customerId)) {
            return;
        }
        long earnAgg = loyaltyTransactionRepository.sumPointsBySaleAndType(saleId, LoyaltyTxnTypes.EARN);
        long redeemedAgg = loyaltyTransactionRepository.sumPointsBySaleAndType(saleId, LoyaltyTxnTypes.REDEEM);
        int earn = (int) earnAgg;
        int redeemed = (int) redeemedAgg;
        if (earn == 0 && redeemed == 0) {
            return;
        }
        CreditAccount acc = creditAccountRepository.findByCustomerIdAndBusinessIdForUpdate(customerId, businessId)
                .orElseThrow();
        acc.setLoyaltyPoints(acc.getLoyaltyPoints() - earn + redeemed);
        acc.setLastActivityAt(Instant.now());
        creditAccountRepository.save(acc);
        loyaltyTransactionRepository.save(tx(businessId, acc.getId(), saleId, LoyaltyTxnTypes.ADJUST_CLAW_VOID, redeemed - earn));
    }

    @Transactional
    public void proportionallyAdjustAfterRefund(
            String businessId,
            String saleId,
            String customerId,
            BigDecimal saleGrandOriginal,
            BigDecimal refundMoneyThisBatch
    ) {
        if (blank(customerId) || refundMoneyThisBatch.signum() <= 0 || saleGrandOriginal.signum() <= 0) {
            return;
        }
        long earnedAgg = loyaltyTransactionRepository.sumPointsBySaleAndType(saleId, LoyaltyTxnTypes.EARN);
        long redeemedAgg = loyaltyTransactionRepository.sumPointsBySaleAndType(saleId, LoyaltyTxnTypes.REDEEM);
        int earned = (int) earnedAgg;
        int redeemed = (int) redeemedAgg;
        if (earned == 0 && redeemed == 0) {
            return;
        }
        BigDecimal frac = nz(refundMoneyThisBatch).divide(nz(saleGrandOriginal), 8, RoundingMode.HALF_UP);
        int clawEarn = safeFloor(multiply(BigDecimal.valueOf(earned), frac));
        int restoreRedeem = safeFloor(multiply(BigDecimal.valueOf(redeemed), frac));
        if (clawEarn == 0 && restoreRedeem == 0) {
            return;
        }
        CreditAccount acc = creditAccountRepository.findByCustomerIdAndBusinessIdForUpdate(customerId, businessId)
                .orElseThrow();
        int netRestore = restoreRedeem - clawEarn;
        acc.setLoyaltyPoints(acc.getLoyaltyPoints() + netRestore);
        acc.setLastActivityAt(Instant.now());
        creditAccountRepository.save(acc);
        loyaltyTransactionRepository.save(tx(businessId, acc.getId(), saleId, LoyaltyTxnTypes.ADJUST_REFUND, netRestore));
        if (acc.getLoyaltyPoints() < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Loyalty balance went negative after refund clawback");
        }
    }

    private static int calculateEarnPoints(BigDecimal basket, BusinessCreditSettings settings) {
        BigDecimal rate = settings.getLoyaltyPointsPerKes();
        if (rate.signum() <= 0 || basket.signum() <= 0) {
            return 0;
        }
        return nz(basket).multiply(rate).setScale(0, RoundingMode.FLOOR).intValueExact();
    }

    private static LoyaltyTransaction tx(String businessId, String accountId, String saleId, String type, int pts) {
        LoyaltyTransaction row = new LoyaltyTransaction();
        row.setBusinessId(businessId);
        row.setCreditAccountId(accountId);
        row.setSaleId(saleId);
        row.setTxnType(type);
        row.setPoints(pts);
        return row;
    }

    private CreditAccount loadAccount(String customerId, String businessId) {
        return creditAccountRepository.findByCustomerIdAndBusinessId(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Credit account not found"));
    }

    private BigDecimal kesPerPoint(BusinessCreditSettings settings) {
        BigDecimal v = nz(settings.getLoyaltyKesPerPoint());
        if (v.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loyalty value per point not configured");
        }
        return v;
    }

    private static BigDecimal multiply(BigDecimal a, BigDecimal b) {
        return a.multiply(b);
    }

    private static int safeFloor(BigDecimal v) {
        if (v.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return v.setScale(0, RoundingMode.FLOOR).intValueExact();
    }

    private static BigDecimal nz(BigDecimal raw) {
        if (raw == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return raw.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static void requireCustomer(String customerId) {
        if (blank(customerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer required for loyalty tender");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
