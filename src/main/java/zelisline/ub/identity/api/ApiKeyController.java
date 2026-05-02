package zelisline.ub.identity.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.ApiKeyResponse;
import zelisline.ub.identity.api.dto.CreatedApiKeyResponse;
import zelisline.ub.identity.api.dto.CreateApiKeyRequest;
import zelisline.ub.identity.application.ApiKeyService;
import zelisline.ub.platform.security.CurrentTenantUser;

@Validated
@RestController
@RequestMapping("/api/v1/api-keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'integrations.api_keys.manage')")
    public Page<ApiKeyResponse> list(Pageable pageable, HttpServletRequest http) {
        return apiKeyService.list(http, CurrentTenantUser.require(http), pageable);
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'integrations.api_keys.manage')")
    public CreatedApiKeyResponse create(@Valid @RequestBody CreateApiKeyRequest request, HttpServletRequest http) {
        return apiKeyService.create(http, CurrentTenantUser.require(http), request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(null, 'integrations.api_keys.manage')")
    public void revoke(@PathVariable("id") String id, HttpServletRequest http) {
        apiKeyService.revoke(http, CurrentTenantUser.require(http), id);
    }
}
