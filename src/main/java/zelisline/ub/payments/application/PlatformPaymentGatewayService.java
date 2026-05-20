package zelisline.ub.payments.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.PlatformGatewayRequest;
import zelisline.ub.payments.api.dto.PlatformGatewayResponse;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PlatformPaymentGateway;
import zelisline.ub.payments.repository.PlatformPaymentGatewayRepository;

/**
 * Super-admin service for managing which gateways are available on the platform.
 */
@Service
@RequiredArgsConstructor
public class PlatformPaymentGatewayService {

    private final PlatformPaymentGatewayRepository platformPaymentGatewayRepository;

    @Transactional(readOnly = true)
    public List<PlatformGatewayResponse> listAll() {
        return platformPaymentGatewayRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public PlatformGatewayResponse update(String gatewayType, PlatformGatewayRequest request) {
        GatewayType type = GatewayType.fromWire(gatewayType);
        PlatformPaymentGateway gw = platformPaymentGatewayRepository.findById(type)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Platform gateway not found: " + gatewayType));
        gw.setEnabled(request.isEnabled());
        if (request.supplierPayoutSupported() != null) {
            gw.setSupplierPayoutSupported(request.supplierPayoutSupported());
        }
        gw.setDisplayName(request.displayName());
        if (request.description() != null) {
            gw.setDescription(request.description());
        }
        if (request.logoUrl() != null) {
            gw.setLogoUrl(request.logoUrl());
        }
        gw.setSortOrder(request.sortOrder());
        platformPaymentGatewayRepository.save(gw);
        return toResponse(gw);
    }

    @Transactional(readOnly = true)
    public List<PlatformPaymentGateway> listEnabled() {
        return platformPaymentGatewayRepository.findByIsEnabledTrueOrderBySortOrderAsc();
    }

    private PlatformGatewayResponse toResponse(PlatformPaymentGateway gw) {
        return new PlatformGatewayResponse(
                gw.getGatewayType().name(),
                gw.isEnabled(),
                gw.isSupplierPayoutSupported(),
                gw.getDisplayName(),
                gw.getDescription(),
                gw.getLogoUrl(),
                gw.getSortOrder(),
                gw.getCreatedAt(),
                gw.getUpdatedAt()
        );
    }
}
