package zelisline.ub.kplc.application;

import java.time.Instant;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.kplc.api.dto.PublicKplcConfigResponse;
import zelisline.ub.kplc.api.dto.PublicKplcMeterResponse;
import zelisline.ub.kplc.api.dto.PublicKplcTokenHistoryResponse;
import zelisline.ub.kplc.api.dto.PublicKplcTokenResponse;
import zelisline.ub.kplc.domain.CustomerKplcMeter;
import zelisline.ub.kplc.domain.KplcMeterNumbers;
import zelisline.ub.kplc.repository.CustomerKplcMeterRepository;

@Service
@RequiredArgsConstructor
public class PublicKplcService {

    static final String COMING_SOON =
            "We're still working on buying tokens here. For now you can look up tokens you've already bought.";

    private final CustomerKplcMeterRepository meterRepository;
    private final KplcTokenLookupClient lookupClient;

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
        return new PublicKplcTokenHistoryResponse(meter, false, COMING_SOON, tokens);
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
}
