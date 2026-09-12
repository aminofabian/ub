package zelisline.ub.marketplace.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.MarketplaceEscrowHoldResponse;
import zelisline.ub.marketplace.application.MarketplaceEscrowService;
import zelisline.ub.marketplace.domain.MarketplaceEscrowReleaseTriggers;

@RestController
@RequestMapping("/api/v1/super-admin/marketplace/escrow-holds")
@RequiredArgsConstructor
public class SuperAdminMarketplaceEscrowController {

    private final MarketplaceEscrowService escrowService;

    @GetMapping
    public List<MarketplaceEscrowHoldResponse> list(@RequestParam(defaultValue = "50") int limit) {
        return escrowService.listForSuperAdmin(limit);
    }

    @PostMapping("/{holdId}/release")
    public MarketplaceEscrowHoldResponse release(@PathVariable String holdId) {
        return escrowService.releaseManual(
                null,
                holdId,
                MarketplaceEscrowReleaseTriggers.MANUAL_SA,
                true);
    }
}
