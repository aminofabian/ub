package zelisline.ub.storefront.application;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.ShopperPhoneSessionRequest;
import zelisline.ub.credits.api.dto.ShopperPhoneSessionResponse;
import zelisline.ub.credits.application.CustomerDirectoryService;
import zelisline.ub.credits.application.CustomerPhoneVerificationService;
import zelisline.ub.credits.application.MpesaPayerIdentityService;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.identity.api.dto.LoginResponse;
import zelisline.ub.identity.application.AuthService;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.platform.pageseal.api.dto.PageSealUnlockResponse;
import zelisline.ub.platform.pageseal.application.PageSealService;

@Service
@RequiredArgsConstructor
public class ShopperPhoneSessionService {

    private static final String BUYER_ROLE_KEY = "buyer";

    private final CustomerPhoneVerificationService phoneVerificationService;
    private final PageSealService pageSealService;
    private final CustomerDirectoryService customerDirectoryService;
    private final CustomerRepository customerRepository;
    private final MpesaPayerIdentityService mpesaPayerIdentityService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final ShopperAccountService shopperAccountService;

    @Transactional
    public ShopperPhoneSessionResponse complete(
            String businessId,
            ShopperPhoneSessionRequest body,
            HttpServletRequest http
    ) {
        String phone = phoneVerificationService.consumeRegistrationToken(
                businessId, body.phoneVerificationToken(), body.phone());
        String local07 = KenyanPhoneForms.toLocal07(phone);
        if (local07 == null) {
            local07 = phone;
        }

        boolean hasPin = pageSealService.customerTabHasPin(businessId, local07);
        String displayName = firstNonBlank(
                body.name(),
                pageSealService.customerTabDisplayName(businessId, local07),
                local07);

        Customer customer = shopperAccountService.ensureCustomerForVerifiedPhone(
                businessId, local07, displayName, null);
        customerDirectoryService.ensureCreditAccount(businessId, customer.getId());
        mpesaPayerIdentityService.markSelfVerified(customer, local07);

        PageSealUnlockResponse unlock = pageSealService.setOrVerifyShopperPin(
                businessId, local07, body.pin(), body.confirmPin(), hasPin);

        User user = findOrCreateBuyer(businessId, local07, customer, displayName);
        LoginResponse tokens = authService.issueSessionForUser(user, http);
        return new ShopperPhoneSessionResponse(
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.user(),
                local07,
                unlock.unlockToken(),
                !hasPin);
    }

    private User findOrCreateBuyer(String businessId, String local07, Customer customer, String displayName) {
        Role buyer = resolveBuyerRole(businessId);
        User existing = findBuyerByPhone(businessId, local07, buyer.getId());
        if (existing == null) {
            existing = userRepository
                    .findByBusinessIdAndEmailAndDeletedAtIsNull(businessId, ShopperPhoneEmails.forLocal07(local07))
                    .filter(u -> buyer.getId().equals(u.getRoleId()))
                    .orElse(null);
        }
        if (existing == null && customer.getEmail() != null && !customer.getEmail().isBlank()
                && !ShopperPhoneEmails.isSynthetic(customer.getEmail())) {
            existing = userRepository
                    .findByBusinessIdAndEmailAndDeletedAtIsNull(
                            businessId, customer.getEmail().trim().toLowerCase())
                    .filter(u -> buyer.getId().equals(u.getRoleId()))
                    .orElse(null);
        }
        if (existing != null) {
            existing.setPhone(local07);
            if (existing.getName() == null || existing.getName().isBlank()
                    || ShopperPhoneEmails.isSynthetic(existing.getEmail())) {
                existing.setName(displayName);
            }
            return userRepository.save(existing);
        }

        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setBusinessId(businessId);
        user.setEmail(ShopperPhoneEmails.forLocal07(local07));
        user.setPhone(local07);
        user.setName(displayName);
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setStatus(UserStatus.ACTIVE);
        user.setRoleId(buyer.getId());
        return userRepository.save(user);
    }

    private User findBuyerByPhone(String businessId, String local07, String buyerRoleId) {
        List<User> matches = userRepository.findByBusinessIdAndPhoneAndDeletedAtIsNull(businessId, local07);
        for (User user : matches) {
            if (buyerRoleId.equals(user.getRoleId())) {
                return user;
            }
        }
        return null;
    }

    private Role resolveBuyerRole(String businessId) {
        return roleRepository.findTenantRoleByKey(businessId, BUYER_ROLE_KEY)
                .or(() -> roleRepository.findSystemRoleByKey(BUYER_ROLE_KEY))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Shopper sign-in is not available: buyer role is not configured"));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
