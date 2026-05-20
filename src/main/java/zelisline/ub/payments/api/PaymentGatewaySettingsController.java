package zelisline.ub.payments.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.AvailableGatewayResponse;
import zelisline.ub.payments.api.dto.GatewayConfigRequest;
import zelisline.ub.payments.api.dto.GatewayConfigResponse;
import zelisline.ub.payments.api.dto.GatewayCredentialSettingsResponse;
import zelisline.ub.payments.api.dto.TestConnectionResponse;
import zelisline.ub.payments.application.PaymentGatewayConfigService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Tenant dashboard endpoints for managing payment gateway configurations.
 *
 * <p>All endpoints require tenant authentication and a resolved
 * business context from the domain / header.
 */
@Validated
@RestController
@RequestMapping("/api/v1/payments/gateways")
@RequiredArgsConstructor
public class PaymentGatewaySettingsController {

    private final PaymentGatewayConfigService configService;

    // ── Available gateways ──────────────────────────────────────────

    @GetMapping("/available")
    @PreAuthorize("hasPermission(null, 'payments.gateways.read')")
    public List<AvailableGatewayResponse> listAvailable(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return configService.listAvailable(TenantRequestIds.resolveBusinessId(request));
    }

    // ── CRUD ────────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasPermission(null, 'payments.gateways.read')")
    public List<GatewayConfigResponse> listConfigs(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return configService.listConfigs(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'payments.gateways.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public GatewayConfigResponse create(
            @Valid @RequestBody GatewayConfigRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return configService.create(TenantRequestIds.resolveBusinessId(request), body);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'payments.gateways.read')")
    public GatewayConfigResponse get(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return configService.getConfig(TenantRequestIds.resolveBusinessId(request), id);
    }

    @GetMapping("/{id}/credential-settings")
    @PreAuthorize("hasPermission(null, 'payments.gateways.read')")
    public GatewayCredentialSettingsResponse credentialSettings(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return configService.getCredentialSettings(TenantRequestIds.resolveBusinessId(request), id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'payments.gateways.write')")
    public GatewayConfigResponse update(
            @PathVariable String id,
            @Valid @RequestBody GatewayConfigRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return configService.update(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'payments.gateways.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        configService.delete(TenantRequestIds.resolveBusinessId(request), id);
    }

    // ── Lifecycle ───────────────────────────────────────────────────

    @PostMapping("/{id}/test")
    @PreAuthorize("hasPermission(null, 'payments.gateways.write')")
    public TestConnectionResponse testConnection(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return configService.testConnection(TenantRequestIds.resolveBusinessId(request), id);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasPermission(null, 'payments.gateways.write')")
    public GatewayConfigResponse activate(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return configService.activate(TenantRequestIds.resolveBusinessId(request), id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasPermission(null, 'payments.gateways.write')")
    public GatewayConfigResponse deactivate(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return configService.deactivate(TenantRequestIds.resolveBusinessId(request), id);
    }
}
