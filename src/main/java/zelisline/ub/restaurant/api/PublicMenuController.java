package zelisline.ub.restaurant.api;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.restaurant.api.dto.OrderDtos.PublicMenu;
import zelisline.ub.restaurant.application.PublicTableOrderService;

/**
 * The menu on a restaurant's public web page. Nobody is signed in: the business comes from the
 * slug in the path, never from a header. Read-only, and only when the owner has switched it on.
 */
@RestController
@RequestMapping("/api/v1/public/businesses/{slug}/menu")
@RequiredArgsConstructor
public class PublicMenuController {

    private final PublicTableOrderService service;

    @GetMapping
    public ResponseEntity<PublicMenu> menu(@PathVariable String slug, HttpServletRequest request) {
        String address = zelisline.ub.platform.security.ClientIpResolver.resolve(request);
        if (address != null && address.length() > 64) {
            address = address.substring(0, 64);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(service.webMenu(slug, address));
    }
}
