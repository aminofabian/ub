package zelisline.ub.support.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.support.domain.SupportMessageReaction;

public interface SupportMessageReactionRepository
        extends JpaRepository<SupportMessageReaction, String> {

    Optional<SupportMessageReaction> findByMessageIdAndActorTypeAndActorRefAndEmoji(
            String messageId, String actorType, String actorRef, String emoji);

    List<SupportMessageReaction> findByMessageIdInOrderByCreatedAtAsc(
            Collection<String> messageIds);

    List<SupportMessageReaction> findByMessageId(String messageId);

    /** Distinct emojis this actor has already placed on a message (abuse bound). */
    long countByMessageIdAndActorTypeAndActorRef(
            String messageId, String actorType, String actorRef);
}
