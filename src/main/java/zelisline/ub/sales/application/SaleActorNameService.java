package zelisline.ub.sales.application;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class SaleActorNameService {

    private final UserRepository userRepository;

    public String resolveSoldByName(String businessId, String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                .map(u -> {
                    if (u.getName() != null && !u.getName().isBlank()) {
                        return u.getName().trim();
                    }
                    if (u.getEmail() != null && !u.getEmail().isBlank()) {
                        return u.getEmail().trim();
                    }
                    return null;
                })
                .orElse(null);
    }
}
