package zelisline.ub.marketplace.application;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.marketplace.api.dto.BuyerSessionResponse;
import zelisline.ub.marketplace.domain.MarketplaceBuyerSession;
import zelisline.ub.marketplace.repository.MarketplaceBuyerSessionRepository;
import zelisline.ub.storefront.application.ShopperIdentifyService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Who a buyer on the marketplace is (ADR 0043, decision D16).
 *
 * <p>A buyer proves they hold a phone number with a texted code, the same code the platform's
 * sign-in door already sends. That opens a session. The shops the buyer may order for are the
 * shops where an active staff member has that phone number, looked up on every request, so a
 * person removed from a shop loses it at once. The session is not a shop sign-in: it reads none
 * of a shop's books and can do one thing, place trade orders in that shop's name.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceBuyerSessionService {

    static final Duration SESSION_TTL = Duration.ofHours(12);
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MarketplaceBuyerSessionRepository sessionRepository;
    private final ShopperIdentifyService shopperIdentifyService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BusinessRepository businessRepository;

    /** Who is ordering, for which shop. */
    public record Buyer(String phone, String businessId, String businessName) {
    }

    /** Spends the single-use verification token and opens a session for that phone number. */
    @Transactional
    public BuyerSessionResponse open(String rawPhone, String verificationToken) {
        String phone = shopperIdentifyService.consumeVerifiedPhone(rawPhone, verificationToken);
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        MarketplaceBuyerSession session = new MarketplaceBuyerSession();
        session.setTokenHash(TokenHasher.sha256Hex(token));
        session.setPhone(phone);
        session.setExpiresAt(Instant.now().plus(SESSION_TTL));
        sessionRepository.save(session);
        return new BuyerSessionResponse(token, session.getExpiresAt(), shopsFor(phone));
    }

    /** The shops this session may order for, as they stand now. */
    @Transactional(readOnly = true)
    public BuyerSessionResponse current(String rawToken) {
        MarketplaceBuyerSession session = requireSession(rawToken);
        return new BuyerSessionResponse(null, session.getExpiresAt(), shopsFor(session.getPhone()));
    }

    /**
     * The buyer, ordering for this shop. Refused unless the session's phone number belongs to an
     * active staff member of that very shop right now.
     */
    @Transactional(readOnly = true)
    public Buyer requireBuyerFor(String rawToken, String businessId) {
        MarketplaceBuyerSession session = requireSession(rawToken);
        for (BuyerSessionResponse.Shop shop : shopsFor(session.getPhone())) {
            if (shop.businessId().equals(businessId)) {
                return new Buyer(session.getPhone(), shop.businessId(), shop.name());
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot order for that shop");
    }

    @Transactional
    public void close(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            sessionRepository.deleteByTokenHash(TokenHasher.sha256Hex(rawToken.trim()));
        }
    }

    private MarketplaceBuyerSession requireSession(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in with your phone number first");
        }
        return sessionRepository.findByTokenHash(TokenHasher.sha256Hex(rawToken.trim()))
                .filter(s -> s.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Sign in with your phone number again"));
    }

    /** Shops where an active staff member (not a shopper account) has this phone number. */
    private List<BuyerSessionResponse.Shop> shopsFor(String phone) {
        Set<String> businessIds = new LinkedHashSet<>();
        for (User user : userRepository.findAllActiveByPhoneIn(KenyanPhoneForms.lookupCandidates(phone))) {
            if (user.getBusinessId() != null && !isShopperAccount(user)) {
                businessIds.add(user.getBusinessId());
            }
        }
        List<BuyerSessionResponse.Shop> shops = new ArrayList<>();
        for (Business shop : businessRepository.findAllById(businessIds)) {
            shops.add(new BuyerSessionResponse.Shop(shop.getId(), shop.getName()));
        }
        shops.sort((a, b) -> String.valueOf(a.name()).compareToIgnoreCase(String.valueOf(b.name())));
        return shops;
    }

    private boolean isShopperAccount(User user) {
        String roleId = user.getRoleId();
        if (roleId == null || roleId.isBlank()) {
            return false;
        }
        return roleRepository.findByIdAndDeletedAtIsNull(roleId)
                .map(Role::getRoleKey)
                .map("buyer"::equalsIgnoreCase)
                .orElse(false);
    }
}
