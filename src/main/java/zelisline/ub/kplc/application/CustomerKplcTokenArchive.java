package zelisline.ub.kplc.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.kplc.api.dto.PublicKplcSpendStatsResponse;
import zelisline.ub.kplc.api.dto.PublicKplcTokenResponse;
import zelisline.ub.kplc.domain.CustomerKplcToken;
import zelisline.ub.kplc.repository.CustomerKplcTokenRepository;

@Component
@RequiredArgsConstructor
public class CustomerKplcTokenArchive {

    private static final Logger log = LoggerFactory.getLogger(CustomerKplcTokenArchive.class);

    private final CustomerKplcTokenRepository tokenRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void rememberFetched(
            String businessId,
            String customerId,
            String meterNumber,
            List<PublicKplcTokenResponse> tokens
    ) {
        if (tokens == null || tokens.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (PublicKplcTokenResponse token : tokens) {
            if (token == null) {
                continue;
            }
            String tokenNo = KplcTokenIdentity.normalizeTokenNo(token.tokenNo());
            if (tokenNo == null) {
                continue;
            }
            Instant purchasedAt = KplcTokenIdentity.matchInstant(token.purchasedAt());
            CustomerKplcToken existing = tokenRepository
                    .findByBusinessIdAndCustomerIdAndTokenNo(businessId, customerId, tokenNo)
                    .orElse(null);
            if (existing == null && purchasedAt != null) {
                existing = findByPurchase(businessId, customerId, meterNumber, purchasedAt, token);
            }
            if (existing != null) {
                touch(existing, token, meterNumber, purchasedAt, now);
                continue;
            }
            CustomerKplcToken row = new CustomerKplcToken();
            row.setBusinessId(businessId);
            row.setCustomerId(customerId);
            row.setMeterNumber(meterNumber);
            row.setTokenNo(tokenNo);
            row.setPurchasedAt(purchasedAt);
            row.setAmount(token.amount());
            row.setUnits(token.units());
            row.setReceiptNo(blankToNull(token.receiptNo()));
            row.setPaymentMethod(blankToNull(token.paymentMethod()));
            row.setConceptsJson(conceptsJson(token));
            row.setFirstSeenAt(now);
            row.setLastSeenAt(now);
            try {
                tokenRepository.save(row);
            } catch (DataIntegrityViolationException e) {
                tokenRepository.findByBusinessIdAndCustomerIdAndTokenNo(businessId, customerId, tokenNo)
                        .ifPresent(dup -> touch(dup, token, meterNumber, purchasedAt, now));
            }
        }
    }

    @Transactional(readOnly = true)
    public PublicKplcSpendStatsResponse stats(String businessId, String customerId, String meterNumber) {
        List<CustomerKplcToken> stored = tokenRepository
                .findByBusinessIdAndCustomerIdAndMeterNumberOrderByPurchasedAtDesc(
                        businessId, customerId, meterNumber);
        List<PublicKplcTokenResponse> asTokens = new ArrayList<>(stored.size());
        for (CustomerKplcToken row : stored) {
            asTokens.add(new PublicKplcTokenResponse(
                    row.getPurchasedAt(),
                    row.getAmount(),
                    row.getUnits(),
                    row.getTokenNo(),
                    row.getReceiptNo(),
                    row.getPaymentMethod(),
                    List.of()));
        }
        return KplcSpendStats.from(asTokens);
    }

    private CustomerKplcToken findByPurchase(
            String businessId,
            String customerId,
            String meterNumber,
            Instant purchasedAt,
            PublicKplcTokenResponse token
    ) {
        if (token.amount() != null) {
            CustomerKplcToken byAmount = tokenRepository
                    .findFirstByBusinessIdAndCustomerIdAndMeterNumberAndPurchasedAtAndAmount(
                            businessId, customerId, meterNumber, purchasedAt, token.amount())
                    .orElse(null);
            if (byAmount != null) {
                return byAmount;
            }
        }
        return tokenRepository
                .findFirstByBusinessIdAndCustomerIdAndMeterNumberAndPurchasedAt(
                        businessId, customerId, meterNumber, purchasedAt)
                .orElse(null);
    }

    private void touch(
            CustomerKplcToken existing,
            PublicKplcTokenResponse token,
            String meterNumber,
            Instant purchasedAt,
            Instant now
    ) {
        existing.setMeterNumber(meterNumber);
        if (purchasedAt != null) {
            existing.setPurchasedAt(purchasedAt);
        }
        if (token.amount() != null) {
            existing.setAmount(token.amount());
        }
        if (token.units() != null) {
            existing.setUnits(token.units());
        }
        if (blankToNull(token.receiptNo()) != null) {
            existing.setReceiptNo(token.receiptNo());
        }
        if (blankToNull(token.paymentMethod()) != null) {
            existing.setPaymentMethod(token.paymentMethod());
        }
        String json = conceptsJson(token);
        if (json != null) {
            existing.setConceptsJson(json);
        }
        existing.setLastSeenAt(now);
        tokenRepository.save(existing);
    }

    private String conceptsJson(PublicKplcTokenResponse token) {
        if (token.concepts() == null || token.concepts().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(token.concepts());
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize KPLC token concepts: {}", e.getMessage());
            return null;
        }
    }

    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }
}
