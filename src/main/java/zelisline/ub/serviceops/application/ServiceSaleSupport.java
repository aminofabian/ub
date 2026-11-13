package zelisline.ub.serviceops.application;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.serviceops.domain.ServiceOffering;
import zelisline.ub.serviceops.domain.ServiceStaffLink;
import zelisline.ub.serviceops.repository.ServiceStaffLinkRepository;

/**
 * What the sale path needs to know about a service line: that the service can be sold, that the
 * performer may perform it, and the name, duration and price to snapshot onto the sale (ADR 0014).
 */
@Service
@RequiredArgsConstructor
public class ServiceSaleSupport {

    private final ServiceCatalogService serviceCatalogService;
    private final ServiceStaffLinkRepository staffLinkRepository;
    private final StaffProfileService staffProfileService;

    /** Snapshot of a service at the moment it is sold. */
    public record ServiceLine(
            String serviceId,
            String name,
            int durationMinutes,
            BigDecimal price,
            boolean priceIsVariable,
            String commissionType,
            BigDecimal commissionValue
    ) {
    }

    /**
     * @throws ResponseStatusException 400 when the service is unknown, deleted or switched off,
     *         or the performer is not a current staff member of this business, or is not among
     *         the staff allowed to perform this service
     */
    @Transactional(readOnly = true)
    public ServiceLine resolve(String businessId, String serviceId, String performerStaffProfileId) {
        if (serviceId == null || serviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a service");
        }
        if (performerStaffProfileId == null || performerStaffProfileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose who performed the service");
        }
        ServiceOffering service = serviceCatalogService.requireSellable(businessId, serviceId.trim());
        String performer = performerStaffProfileId.trim();
        // The performer must be a current staff member who serves customers ("bookable").
        if (!staffProfileService.findBookableProfileIds(businessId).contains(performer)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Staff member not found, or not marked as serving customers");
        }
        List<ServiceStaffLink> allowed =
                staffLinkRepository.findByBusinessIdAndServiceIdIn(businessId, List.of(service.getId()));
        if (!allowed.isEmpty()
                && allowed.stream().noneMatch(l -> performer.equals(l.getId().getStaffProfileId()))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "This staff member does not perform " + service.getName());
        }
        return new ServiceLine(
                service.getId(),
                service.getName(),
                service.getDurationMinutes(),
                service.getPrice(),
                service.isPriceIsVariable(),
                service.getCommissionType(),
                service.getCommissionValue());
    }
}
