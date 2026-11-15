package zelisline.ub.sales.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.sales.api.dto.SaleResponse;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.domain.SaleItem;
import zelisline.ub.sales.repository.SaleItemRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.serviceops.application.StaffEarningsService;
import zelisline.ub.serviceops.domain.ServiceHelpers;

/**
 * Corrects who did a service after it was paid for (ADR 0014, T7 "reassign performer"): the
 * sale line names the right person and the earnings follow. The sale's money does not move.
 */
@Service
@RequiredArgsConstructor
public class SalePerformerService {

    private final SaleRepository saleRepository;
    private final SaleItemRepository saleItemRepository;
    private final StaffProfileService staffProfileService;
    private final StaffEarningsService staffEarningsService;
    private final SaleService saleService;

    @Transactional
    public SaleResponse move(
            String businessId, String saleId, String saleItemId, String staffProfileId, String reason, String userId) {
        Sale sale = saleRepository.findByIdAndBusinessId(saleId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale not found"));
        SaleItem item = saleItemRepository.findByIdAndSaleId(saleItemId, saleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sale line not found"));
        if (!item.isService()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a service has a person who did it");
        }
        if (!SalesConstants.SALE_STATUS_COMPLETED.equals(sale.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This sale was voided; there is nothing to move");
        }
        String to = staffProfileId.trim();
        if (to.equals(item.getPerformerStaffProfileId())) {
            return saleService.requireSale(businessId, saleId);
        }
        // Same rule as at the till: a current team member who serves customers, of this business.
        if (!staffProfileService.findBookableProfileIds(businessId).contains(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Staff member not found, or not marked as serving customers");
        }
        List<ServiceHelpers.Helper> helpers = ServiceHelpers.decode(item.getServiceHelpers());
        if (helpers.stream().anyMatch(h -> to.equals(h.staffProfileId()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "They already helped on this service. Void the sale and ring it again to change the team");
        }
        item.setPerformerStaffProfileId(to);
        saleItemRepository.save(item);
        staffEarningsService.movePerformer(
                businessId,
                saleId,
                new StaffEarningsService.SaleLine(
                        item.getId(), true, item.getServiceId(), to, item.getLineTotal(), item.getTaxAmount(),
                        item.getQuantity(), helpers, item.getPerformerAmount()),
                reason,
                userId);
        return saleService.requireSale(businessId, saleId);
    }
}
