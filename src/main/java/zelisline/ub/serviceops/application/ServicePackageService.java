package zelisline.ub.serviceops.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.WalletTxnTypes;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.serviceops.api.dto.PackageDtos.CustomerPackageResponse;
import zelisline.ub.serviceops.api.dto.PackageDtos.PackageOfferResponse;
import zelisline.ub.serviceops.api.dto.PackageDtos.SellPackageRequest;
import zelisline.ub.serviceops.api.dto.PackageDtos.SetPackageOfferRequest;
import zelisline.ub.serviceops.domain.CustomerServicePackage;
import zelisline.ub.serviceops.domain.ServiceOffering;
import zelisline.ub.serviceops.repository.CustomerServicePackageRepository;
import zelisline.ub.serviceops.repository.ServiceOfferingRepository;
import zelisline.ub.tenancy.repository.BranchRepository;

/**
 * Prepaid packages: "five haircuts for the price of four" (ADR 0015).
 *
 * <p>Selling one takes the money into {@code DEFERRED_SERVICE_REVENUE}; it is not income yet.
 * Each time a session is used, that session's share moves to the customer's wallet and pays for
 * the visit there, so income is recognised visit by visit through the ordinary sale. The last
 * session carries whatever rounding left over, so the liability always ends at exactly zero.</p>
 */
@Service
@RequiredArgsConstructor
public class ServicePackageService {

    private final ServiceOfferingRepository serviceRepository;
    private final CustomerServicePackageRepository packageRepository;
    private final CustomerRepository customerRepository;
    private final BranchRepository branchRepository;
    private final HeldMoneyService heldMoneyService;

    // ------------------------------------------------------------------- offers

    @Transactional(readOnly = true)
    public List<PackageOfferResponse> offers(String businessId) {
        List<PackageOfferResponse> out = new ArrayList<>();
        for (ServiceOffering service : serviceRepository.findByBusinessIdAndPackageSessionsIsNotNullAndDeletedAtIsNull(businessId)) {
            if (service.isActive() && service.getPackagePrice() != null) {
                out.add(toOffer(service));
            }
        }
        return out;
    }

    /** Sets the package offered for a service; null sessions removes it. Packages already sold are untouched. */
    @Transactional
    public PackageOfferResponse setOffer(String businessId, String serviceId, SetPackageOfferRequest request) {
        ServiceOffering service = serviceRepository.findByIdAndBusinessIdAndDeletedAtIsNull(serviceId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found"));
        if (request.sessions() == null) {
            service.setPackageSessions(null);
            service.setPackagePrice(null);
            service.setPackageValidDays(null);
            serviceRepository.save(service);
            return null;
        }
        if (request.price() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the price of the package");
        }
        service.setPackageSessions(request.sessions());
        service.setPackagePrice(HeldMoneyService.requireAmount(request.price()));
        service.setPackageValidDays(request.validDays());
        serviceRepository.save(service);
        return toOffer(service);
    }

    // --------------------------------------------------------------------- sell

    /** Sells a package to a customer. The same {@code Idempotency-Key} again returns the first sale. */
    @Transactional
    public CustomerPackageResponse sell(
            String businessId, SellPackageRequest request, String idempotencyKey, String userId, String tillDeviceKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
        }
        String key = idempotencyKey.trim();
        if (key.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is too long");
        }
        var existing = packageRepository.findByBusinessIdAndIdempotencyKey(businessId, key);
        if (existing.isPresent()) {
            CustomerServicePackage first = existing.get();
            if (!first.getCustomerId().equals(request.customerId().trim())
                    || !first.getServiceId().equals(request.serviceId().trim())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This was already used for another package; try again");
            }
            return toResponse(first);
        }
        String branchId = request.branchId().trim();
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
        customerRepository.findByIdAndBusinessIdAndDeletedAtIsNull(request.customerId().trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer not found"));
        ServiceOffering service = serviceRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(request.serviceId().trim(), businessId)
                .filter(ServiceOffering::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Service not found"));
        if (service.getPackageSessions() == null || service.getPackagePrice() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No package is offered for " + service.getName());
        }

        CustomerServicePackage row = new CustomerServicePackage();
        row.setId(UUID.randomUUID().toString());
        row.setBusinessId(businessId);
        row.setBranchId(branchId);
        row.setCustomerId(request.customerId().trim());
        row.setServiceId(service.getId());
        row.setServiceName(service.getName().length() > 191 ? service.getName().substring(0, 191) : service.getName());
        row.setSessionsTotal(service.getPackageSessions());
        row.setSessionsRemaining(service.getPackageSessions());
        row.setPricePaid(service.getPackagePrice());
        row.setValueRemaining(service.getPackagePrice());
        row.setPaymentMethod(request.method().trim().toLowerCase(Locale.ROOT));
        row.setReference(request.reference() == null || request.reference().isBlank() ? null : request.reference().trim());
        row.setSoldBy(userId);
        row.setIdempotencyKey(key);
        if (service.getPackageValidDays() != null) {
            row.setExpiresAt(Instant.now().plus(service.getPackageValidDays(), ChronoUnit.DAYS));
        }
        try {
            packageRepository.saveAndFlush(row);
        } catch (DataIntegrityViolationException ex) {
            // The same key from another till a moment ago: nothing was taken here.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This was already recorded; refresh and check");
        }
        heldMoneyService.receive(
                businessId, branchId, LedgerAccountCodes.DEFERRED_SERVICE_REVENUE,
                HeldMoneyService.SOURCE_PACKAGE_SOLD, row.getId(), row.getPricePaid(), row.getPaymentMethod(),
                "Package sold: " + row.getSessionsTotal() + " x " + row.getServiceName(), userId, tillDeviceKey);
        return toResponse(row);
    }

    @Transactional(readOnly = true)
    public List<CustomerPackageResponse> forCustomer(String businessId, String customerId) {
        return packageRepository.findByBusinessIdAndCustomerIdOrderByCreatedAtDesc(businessId, customerId.trim())
                .stream().map(ServicePackageService::toResponse).toList();
    }

    // ------------------------------------------------------------------- redeem

    /**
     * Uses one session: its value moves from deferred revenue to the customer's wallet, where the
     * caller's sale spends it. Runs in the caller's transaction, so a sale that then fails gives
     * the session back.
     *
     * @param serviceIds the services on the visit; the package must be for one of them
     * @return the value of the session used
     */
    @Transactional
    public BigDecimal redeemSession(
            String businessId, String packageId, String customerId, Collection<String> serviceIds, String branchId) {
        CustomerServicePackage row = packageRepository.findByIdAndBusinessIdForUpdate(packageId.trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));
        if (customerId == null || !row.getCustomerId().equals(customerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This package belongs to another customer");
        }
        if (!serviceIds.contains(row.getServiceId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This package is for " + row.getServiceName() + ", which is not on the ticket");
        }
        if (row.getSessionsRemaining() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This package has no sessions left");
        }
        if (row.getExpiresAt() != null && row.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This package has run out of time");
        }
        BigDecimal value = row.getSessionsRemaining() == 1
                ? row.getValueRemaining()
                : row.getPricePaid().divide(BigDecimal.valueOf(row.getSessionsTotal()), 2, RoundingMode.DOWN);
        row.setSessionsRemaining(row.getSessionsRemaining() - 1);
        row.setValueRemaining(row.getValueRemaining().subtract(value));
        packageRepository.saveAndFlush(row);
        heldMoneyService.releaseToWallet(
                businessId, branchId, LedgerAccountCodes.DEFERRED_SERVICE_REVENUE,
                HeldMoneyService.SOURCE_PACKAGE_SESSION, UUID.randomUUID().toString(), customerId, value,
                WalletTxnTypes.CREDIT_PACKAGE_SESSION,
                "Package session used: " + row.getServiceName());
        return value;
    }

    private static PackageOfferResponse toOffer(ServiceOffering service) {
        return new PackageOfferResponse(service.getId(), service.getName(), service.getPrice(),
                service.getPackageSessions(), service.getPackagePrice(), service.getPackageValidDays());
    }

    private static CustomerPackageResponse toResponse(CustomerServicePackage row) {
        return new CustomerPackageResponse(row.getId(), row.getCustomerId(), row.getServiceId(), row.getServiceName(),
                row.getSessionsTotal(), row.getSessionsRemaining(), row.getPricePaid(), row.getValueRemaining(),
                row.getExpiresAt(), row.getExpiresAt() != null && row.getExpiresAt().isBefore(Instant.now()),
                row.getCreatedAt());
    }
}
