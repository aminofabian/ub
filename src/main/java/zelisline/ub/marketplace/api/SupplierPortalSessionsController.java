package zelisline.ub.marketplace.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalSessionRow;
import zelisline.ub.marketplace.application.SupplierPortalSessionService;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/sessions")
@RequiredArgsConstructor
public class SupplierPortalSessionsController {

    private final SupplierPortalSessionService sessionService;

    @GetMapping
    @PreAuthorize("hasRole('SUPPLIER')")
    public List<SupplierPortalSessionRow> list() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return sessionService.listSessions(principal.userId(), principal.jti());
    }

    @DeleteMapping("/{sessionId}")
    @PreAuthorize("hasRole('SUPPLIER')")
    public Map<String, Object> revoke(@PathVariable String sessionId) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        sessionService.revokeSession(principal.userId(), sessionId, principal.jti());
        return Map.of("ok", true);
    }

    @PostMapping("/logout-all")
    @PreAuthorize("hasRole('SUPPLIER')")
    public Map<String, Object> logoutAll() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        sessionService.revokeAll(principal.userId());
        return Map.of("ok", true);
    }

    @PostMapping("/logout")
    @PreAuthorize("hasRole('SUPPLIER')")
    public Map<String, Object> logout() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        String jti = principal.jti();
        if (jti == null || jti.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing session");
        }
        List<SupplierPortalSessionRow> rows = sessionService.listSessions(principal.userId(), jti);
        rows.stream()
                .filter(SupplierPortalSessionRow::current)
                .findFirst()
                .ifPresent(row -> sessionService.revokeSession(principal.userId(), row.sessionId(), jti));
        return Map.of("ok", true);
    }
}
