package zelisline.ub.finance.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.domain.LedgerAccount;
import zelisline.ub.finance.repository.LedgerAccountRepository;

@Service
@RequiredArgsConstructor
public class LedgerAccountResolver {

    private final LedgerAccountRepository ledgerAccountRepository;

    public LedgerAccount resolve(String businessId, String code) {
        return ledgerAccountRepository.findByBusinessIdAndCode(businessId, code)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Missing ledger account " + code));
    }

    public String resolveId(String businessId, String code) {
        return resolve(businessId, code).getId();
    }

    public LedgerAccount findById(String id) {
        return ledgerAccountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Missing ledger account " + id));
    }
}
