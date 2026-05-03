package zelisline.ub.storefront.api;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.storefront.api.dto.PublicCartResponse;
import zelisline.ub.storefront.api.dto.PublicUpsertCartLineRequest;
import zelisline.ub.storefront.application.PublicWebCartService;

@RestController
@RequestMapping("/api/v1/public/businesses/{slug}/carts")
@RequiredArgsConstructor
public class PublicWebCartController {

    private final PublicWebCartService publicWebCartService;

    @PostMapping
    public ResponseEntity<PublicCartResponse> create(@PathVariable String slug) {
        PublicCartResponse body = publicWebCartService.createCart(slug);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/{cartId}")
    public ResponseEntity<PublicCartResponse> get(@PathVariable String slug, @PathVariable String cartId) {
        PublicCartResponse body = publicWebCartService.getCart(slug, cartId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping("/{cartId}/lines")
    public ResponseEntity<PublicCartResponse> upsertLine(
            @PathVariable String slug,
            @PathVariable String cartId,
            @Valid @RequestBody PublicUpsertCartLineRequest request
    ) {
        PublicCartResponse body = publicWebCartService.upsertLine(slug, cartId, request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @DeleteMapping("/{cartId}/lines/{itemId}")
    public ResponseEntity<PublicCartResponse> removeLine(
            @PathVariable String slug,
            @PathVariable String cartId,
            @PathVariable String itemId
    ) {
        PublicCartResponse body = publicWebCartService.removeLine(slug, cartId, itemId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
