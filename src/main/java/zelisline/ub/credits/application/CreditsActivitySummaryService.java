package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.CreditTxnTypes;
import zelisline.ub.credits.api.dto.CreditsActivitySummaryResponse;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditTransactionRepository;

@Service
@RequiredArgsConstructor
public class CreditsActivitySummaryService {

    private static final int MONEY_SCALE = 2;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Nairobi");

    private final CreditTransactionRepository creditTransactionRepository;
    private final CreditAccountRepository creditAccountRepository;

    @Transactional(readOnly = true)
    public CreditsActivitySummaryResponse summarize(String businessId, LocalDate from, LocalDate to) {
        LocalDate fromDay = from != null ? from : LocalDate.now(BUSINESS_ZONE);
        LocalDate toDay = to != null ? to : fromDay;
        if (toDay.isBefore(fromDay)) {
            LocalDate swap = fromDay;
            fromDay = toDay;
            toDay = swap;
        }

        Instant fromInclusive = fromDay.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant toExclusive = toDay.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();

        BigDecimal paid = money(
                creditTransactionRepository.sumAmountByBusinessIdAndTxnTypeAndCreatedAtRange(
                        businessId,
                        CreditTxnTypes.PAYMENT,
                        fromInclusive,
                        toExclusive));
        long paymentCount = creditTransactionRepository.countByBusinessIdAndTxnTypeAndCreatedAtRange(
                businessId,
                CreditTxnTypes.PAYMENT,
                fromInclusive,
                toExclusive);
        BigDecimal owed = money(creditAccountRepository.sumOutstandingBalanceByBusinessId(businessId));
        long openTabCount = creditAccountRepository.countOutstandingByBusinessId(businessId);

        return new CreditsActivitySummaryResponse(paid, paymentCount, owed, openTabCount);
    }

    private static BigDecimal money(BigDecimal raw) {
        if (raw == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return raw.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
