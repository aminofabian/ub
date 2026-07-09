package zelisline.ub.marketplace.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalInvoiceRow;
import zelisline.ub.purchasing.repository.SupplierInvoiceRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class SupplierPortalInvoicesService {

    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final BusinessRepository businessRepository;

    @Transactional(readOnly = true)
    public List<SupplierPortalInvoiceRow> listInvoices(String marketplaceSupplierId) {
        return supplierInvoiceRepository.findForSupplierPortal(marketplaceSupplierId).stream()
                .map(inv -> new SupplierPortalInvoiceRow(
                        inv.getId(),
                        inv.getBusinessId(),
                        businessRepository.findById(inv.getBusinessId())
                                .map(b -> b.getName())
                                .orElse("Business"),
                        inv.getInvoiceNumber(),
                        inv.getInvoiceDate(),
                        inv.getDueDate(),
                        inv.getSubtotal(),
                        inv.getTaxTotal(),
                        inv.getGrandTotal(),
                        inv.getStatus()))
                .toList();
    }
}
