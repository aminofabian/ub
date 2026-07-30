package zelisline.ub.tenancy.api.dto;

public record PayDomainOrderResponse(
        String orderId,
        String checkoutRequestId,
        String status,
        String message,
        boolean accepted,
        DomainOrderResponse order
) {}
