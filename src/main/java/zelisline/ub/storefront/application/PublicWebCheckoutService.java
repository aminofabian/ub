package zelisline.ub.storefront.application;

import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.storefront.WebOrderStatuses;
import zelisline.ub.storefront.api.dto.PublicCheckoutRequest;
import zelisline.ub.storefront.api.dto.PublicCheckoutResponse;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.domain.WebOrderLine;
import zelisline.ub.storefront.repository.WebCartRepository;
import zelisline.ub.storefront.repository.WebOrderLineRepository;
import zelisline.ub.storefront.repository.WebOrderRepository;

@Service
@RequiredArgsConstructor
public class PublicWebCheckoutService {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final PublicWebCartService publicWebCartService;
    private final WebOrderRepository webOrderRepository;
    private final WebOrderLineRepository webOrderLineRepository;
    private final WebCartRepository webCartRepository;

    @Transactional
    public PublicCheckoutResponse submitCheckout(String slug, String cartId, PublicCheckoutRequest req) {
        PublicWebCartService.CheckoutEligibility elig =
                publicWebCartService.requireCheckoutEligible(slug, cartId.trim());
        String name = req.customerName().trim();
        String phone = req.customerPhone().trim();
        if (name.isEmpty() || phone.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contact details required");
        }
        String email = normalizeOptionalEmail(req.customerEmail());
        String notes = blankToNull(req.notes());

        WebOrder order = new WebOrder();
        order.setBusinessId(elig.ctx().business().getId());
        order.setCartId(elig.cart().getId());
        order.setCatalogBranchId(elig.cart().getCatalogBranchId());
        order.setStatus(WebOrderStatuses.PENDING_PAYMENT);
        order.setCurrency(elig.ctx().business().getCurrency());
        order.setGrandTotal(elig.grandTotal());
        order.setCustomerName(name);
        order.setCustomerPhone(phone);
        order.setCustomerEmail(email);
        order.setNotes(notes);
        webOrderRepository.save(order);

        for (PublicWebCartService.CheckoutLine line : elig.lines()) {
            WebOrderLine ol = new WebOrderLine();
            ol.setOrderId(order.getId());
            ol.setItemId(line.item().getId());
            ol.setItemName(line.item().getName());
            ol.setVariantName(blankToNull(line.item().getVariantName()));
            ol.setQuantity(line.quantity());
            ol.setUnitPrice(line.unitPrice());
            ol.setLineTotal(line.lineTotal());
            ol.setLineIndex(line.lineIndex());
            webOrderLineRepository.save(ol);
        }

        webCartRepository.deleteById(elig.cart().getId());

        return new PublicCheckoutResponse(
                order.getId(),
                order.getStatus(),
                order.getGrandTotal(),
                order.getCurrency(),
                elig.ctx().catalogBranch().getName(),
                order.getCreatedAt());
    }

    private static String normalizeOptionalEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String e = raw.trim();
        if (!EMAIL.matcher(e).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid email");
        }
        return e;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
