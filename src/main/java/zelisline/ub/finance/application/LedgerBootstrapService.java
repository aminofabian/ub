package zelisline.ub.finance.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.domain.LedgerAccount;
import zelisline.ub.finance.repository.LedgerAccountRepository;

@Service
@RequiredArgsConstructor
public class LedgerBootstrapService {

    private final LedgerAccountRepository ledgerAccountRepository;

    @Transactional
    public void ensureStandardAccounts(String businessId) {
        ensure(businessId, LedgerAccountCodes.OPERATING_CASH, "Operating cash", "asset");
        ensure(businessId, LedgerAccountCodes.INVENTORY, "Inventory", "asset");
        ensure(businessId, LedgerAccountCodes.SUPPLIER_ADVANCES, "Supplier advances (prepayments)", "asset");
        ensure(businessId, LedgerAccountCodes.ACCOUNTS_PAYABLE, "Accounts Payable – Suppliers", "liability");
        ensure(businessId, LedgerAccountCodes.GOODS_RECEIVED_NOT_INVOICED, "Goods received not invoiced (GRNI)", "liability");
        ensure(businessId, LedgerAccountCodes.INVENTORY_SHRINKAGE, "Inventory shrinkage (wastage)", "expense");
        ensure(businessId, LedgerAccountCodes.PURCHASE_PRICE_VARIANCE, "Purchase price variance", "expense");
    }

    private void ensure(String businessId, String code, String name, String type) {
        if (!ledgerAccountRepository.existsByBusinessIdAndCode(businessId, code)) {
            ledgerAccountRepository.save(account(businessId, code, name, type));
        }
    }

    private static LedgerAccount account(String businessId, String code, String name, String type) {
        LedgerAccount a = new LedgerAccount();
        a.setBusinessId(businessId);
        a.setCode(code);
        a.setName(name);
        a.setAccountType(type);
        return a;
    }
}
