package zelisline.ub.finance.application;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.domain.LedgerAccount;
import zelisline.ub.finance.repository.LedgerAccountRepository;

@Service
@RequiredArgsConstructor
public class LedgerAccountResolver {

    private final LedgerAccountRepository ledgerAccountRepository;

    public LedgerAccount resolve(String businessId, String code) {
        return ledgerAccountRepository.findByBusinessIdAndCode(businessId, code)
                .orElseThrow(() -> new IllegalStateException("Missing ledger account " + code));
    }

    public String resolveId(String businessId, String code) {
        return resolve(businessId, code).getId();
    }

    public LedgerAccount findById(String id) {
        return ledgerAccountRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Missing ledger account " + id));
    }
}
