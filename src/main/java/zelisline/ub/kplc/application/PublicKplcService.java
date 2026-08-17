package zelisline.ub.kplc.application;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.kplc.api.dto.PublicKplcConfigResponse;
import zelisline.ub.kplc.api.dto.PublicKplcDepletionResponse;
import zelisline.ub.kplc.api.dto.PublicKplcMeterResponse;
import zelisline.ub.kplc.api.dto.PublicKplcSpendStatsResponse;
import zelisline.ub.kplc.api.dto.PublicKplcTokenHistoryResponse;
import zelisline.ub.kplc.api.dto.PublicKplcTokenResponse;
import zelisline.ub.kplc.domain.CustomerKplcMeter;
import zelisline.ub.kplc.domain.KplcMeterNumbers;
import zelisline.ub.kplc.repository.CustomerKplcMeterRepository;

@Service
@RequiredArgsConstructor
public class PublicKplcService {

    private static final Logger log = LoggerFactory.getLogger(PublicKplcService.class);

    static final String COMING_SOON =
            "We're still working on buying tokens here. For now you can look up tokens you've already bought.";

    private final CustomerKplcMeterRepository meterRepository;
    private final KplcTokenLookupClient lookupClient;
    private final CustomerKplcTokenArchive tokenArchive;

    @Transactional(readOnly = true)
    public PublicKplcConfigResponse config(String businessId, String customerId) {
        return new PublicKplcConfigResponse(false, COMING_SOON, meters(businessId, customerId));
    }

    @Transactional
    public PublicKplcConfigResponse saveMeter(String businessId, String customerId, String rawMeter) {
        remember(businessId, customerId, requireMeter(rawMeter));
        return config(businessId, customerId);
    }

    @Transactional
    public PublicKplcConfigResponse removeMeter(String businessId, String customerId, String rawMeter) {
        String meter = requireMeter(rawMeter);
        meterRepository.deleteByBusinessIdAndCustomerIdAndMeterNumber(businessId, customerId, meter);
        return config(businessId, customerId);
    }

    public PublicKplcTokenHistoryResponse history(String businessId, String customerId, String rawMeter) {
        String meter = requireMeter(rawMeter);
        List<PublicKplcTokenResponse> tokens = lookupClient.fetchHistory(meter);
        remember(businessId, customerId, meter);
        try {
            tokenArchive.rememberFetched(businessId, customerId, meter, tokens);
        } catch (Exception e) {
            log.warn("Could not archive KPLC tokens for meterTail={}: {}",
                    meter.length() < 4 ? "****" : meter.substring(meter.length() - 4),
                    e.getMessage());
        }
        PublicKplcSpendStatsResponse stats = tokenArchive.stats(businessId, customerId, meter);
        if (stats.months().isEmpty() && !tokens.isEmpty()) {
            stats = KplcSpendStats.from(tokens);
        }
        List<PublicKplcTokenResponse> forEstimate = tokenArchive.list(businessId, customerId, meter);
        if (forEstimate.isEmpty()) {
            forEstimate = tokens;
        }
        CustomerKplcMeter saved = meterRepository
                .findByBusinessIdAndCustomerIdAndMeterNumber(businessId, customerId, meter)
                .orElse(null);
        PublicKplcDepletionResponse depletion = depletionOf(forEstimate, saved);
        return new PublicKplcTokenHistoryResponse(meter, false, COMING_SOON, tokens, stats, depletion);
    }

    @Transactional
    public PublicKplcDepletionResponse setDepletionAlerts(
            String businessId,
            String customerId,
            String rawMeter,
            boolean enabled
    ) {
        String meter = requireMeter(rawMeter);
        remember(businessId, customerId, meter);
        CustomerKplcMeter saved = meterRepository
                .findByBusinessIdAndCustomerIdAndMeterNumber(businessId, customerId, meter)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Save this meter first, then turn on reminders."));
        saved.setDepletionAlertsEnabled(enabled);
        meterRepository.save(saved);
        List<PublicKplcTokenResponse> stored = tokenArchive.list(businessId, customerId, meter);
        return depletionOf(stored, saved);
    }

    private List<PublicKplcMeterResponse> meters(String businessId, String customerId) {
        return meterRepository.findByBusinessIdAndCustomerIdOrderByLastUsedAtDesc(businessId, customerId)
                .stream()
                .map(row -> new PublicKplcMeterResponse(row.getMeterNumber(), row.getLastUsedAt()))
                .toList();
    }

    private void remember(String businessId, String customerId, String meter) {
        Instant now = Instant.now();
        CustomerKplcMeter existing = meterRepository
                .findByBusinessIdAndCustomerIdAndMeterNumber(businessId, customerId, meter)
                .orElse(null);
        if (existing != null) {
            existing.setLastUsedAt(now);
            meterRepository.save(existing);
            return;
        }
        List<CustomerKplcMeter> saved = meterRepository
                .findByBusinessIdAndCustomerIdOrderByLastUsedAtDesc(businessId, customerId);
        if (saved.size() >= KplcMeterNumbers.MAX_SAVED_PER_CUSTOMER) {
            meterRepository.delete(saved.get(saved.size() - 1));
        }
        CustomerKplcMeter row = new CustomerKplcMeter();
        row.setBusinessId(businessId);
        row.setCustomerId(customerId);
        row.setMeterNumber(meter);
        row.setLastUsedAt(now);
        try {
            meterRepository.save(row);
        } catch (DataIntegrityViolationException e) {
            meterRepository.findByBusinessIdAndCustomerIdAndMeterNumber(businessId, customerId, meter)
                    .ifPresent(dup -> {
                        dup.setLastUsedAt(now);
                        meterRepository.save(dup);
                    });
        }
    }

    private static String requireMeter(String raw) {
        String meter = KplcMeterNumbers.normalize(raw);
        if (meter == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enter the meter number as printed on the meter or last token slip");
        }
        return meter;
    }

    static PublicKplcDepletionResponse depletionOf(
            List<PublicKplcTokenResponse> tokens,
            CustomerKplcMeter meter
    ) {
        boolean alerts = meter != null && meter.isDepletionAlertsEnabled();
        return KplcDepletionEstimator.estimate(tokens, Instant.now())
                .map(estimate -> new PublicKplcDepletionResponse(
                        estimate.estimatedEmptyAt(),
                        estimate.remainingUnits(),
                        estimate.lastPurchaseUnits(),
                        estimate.dailyUseUnits(),
                        estimate.sampleIntervals(),
                        estimate.alreadyEmpty(),
                        alerts))
                .orElse(new PublicKplcDepletionResponse(
                        null, null, null, null, 0, false, alerts));
    }
}
