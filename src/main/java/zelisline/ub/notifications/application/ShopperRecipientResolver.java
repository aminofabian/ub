package zelisline.ub.notifications.application;

import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;

@Component
@RequiredArgsConstructor
public class ShopperRecipientResolver {

    private static final String BUYER_ROLE_KEY = "buyer";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    /**
     * Resolves a registered shopper {@code user_id} from a normalized or raw email, when the
     * account uses the system buyer role.
     */
    public Optional<String> resolveBuyerUserId(String businessId, String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        String norm = email.trim().toLowerCase(Locale.ROOT);
        Optional<User> userOpt = userRepository.findByBusinessIdAndEmailAndDeletedAtIsNull(businessId, norm);
        if (userOpt.isEmpty()) {
            return Optional.empty();
        }
        User user = userOpt.get();
        Optional<Role> buyerRole = roleRepository.findSystemRoleByKey(BUYER_ROLE_KEY);
        if (buyerRole.isEmpty() || !buyerRole.get().getId().equals(user.getRoleId())) {
            return Optional.empty();
        }
        return Optional.of(user.getId());
    }
}
