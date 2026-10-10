package zelisline.ub.messaging.application;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.application.InboxAgentDirectory;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;

/**
 * Resolves inbox hand-off agents from the identity directory: active users of a tenant whose role
 * grants {@code crm.inbox.read}.
 *
 * <p>Lives in {@code messaging} because {@code identity} already depends on {@code messaging}
 * ({@code identity → messaging → crm}), so {@code crm} must not depend on {@code identity}
 * directly. See {@link InboxAgentDirectory} and {@code docs/adr/0011-whatsapp-crm-boundary.md}.
 */
@Component
@RequiredArgsConstructor
public class UserInboxAgentDirectory implements InboxAgentDirectory {

    private static final String INBOX_READ_PERMISSION = "crm.inbox.read";

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public List<Agent> listInboxAgents(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return List.of();
        }
        List<String> ids = userRepository.findIdsWithPermission(businessId, INBOX_READ_PERMISSION);
        if (ids.isEmpty()) {
            return List.of();
        }
        return userRepository.findLiveByIds(ids).stream()
                .sorted(Comparator.comparing(User::getName, String.CASE_INSENSITIVE_ORDER))
                .map(user -> new Agent(user.getId(), user.getName()))
                .toList();
    }
}
