package zelisline.ub.support.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.support.domain.SupportMessage;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, String> {

    List<SupportMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    Optional<SupportMessage> findByConversationIdAndClientMessageId(
            String conversationId, String clientMessageId);

    long countByConversationId(String conversationId);

    long countByConversationIdAndMessageKind(String conversationId, String messageKind);

    void deleteByConversationIdAndMessageKind(String conversationId, String messageKind);

    long countByConversationIdAndSenderTypeAndCreatedAtAfter(
            String conversationId, String senderType, Instant createdAtAfter);

    Optional<SupportMessage> findTopByConversationIdOrderByCreatedAtDesc(String conversationId);

    /** Idempotency keys already spent in a thread — used to de-dupe on merge. */
    @Query("""
            SELECT m.clientMessageId FROM SupportMessage m
            WHERE m.conversationId = :conversationId AND m.clientMessageId IS NOT NULL
            """)
    List<String> findClientMessageIds(@Param("conversationId") String conversationId);

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE SupportMessage m
            SET m.clientMessageId = NULL
            WHERE m.conversationId = :conversationId
              AND m.clientMessageId IN :clientMessageIds
            """)
    int clearClientMessageIds(@Param("conversationId") String conversationId,
                              @Param("clientMessageIds") List<String> clientMessageIds);

    /** Moves a merged thread's messages under the surviving conversation. */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE SupportMessage m
            SET m.conversationId = :targetConversationId
            WHERE m.conversationId = :sourceConversationId
            """)
    int reassignConversation(@Param("targetConversationId") String targetConversationId,
                             @Param("sourceConversationId") String sourceConversationId);

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
