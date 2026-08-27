package zelisline.ub.support.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.support.domain.SupportMessage;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, String> {

    List<SupportMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    long countByConversationId(String conversationId);

    long countByConversationIdAndSenderTypeAndCreatedAtAfter(
            String conversationId, String senderType, Instant createdAtAfter);

    @Modifying
    @Query("""
            UPDATE SupportMessage m
            SET m.readAt = :at
            WHERE m.conversationId = :conversationId
              AND m.senderType = :senderType
              AND m.readAt IS NULL
            """)
    int markRead(@Param("conversationId") String conversationId,
                 @Param("senderType") String senderType,
                 @Param("at") Instant at);
}
