package zelisline.ub.crm.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.crm.domain.CrmMessage;

public interface CrmMessageRepository extends JpaRepository<CrmMessage, String> {

    boolean existsByWaMessageId(String waMessageId);

    Optional<CrmMessage> findByWaMessageId(String waMessageId);

    List<CrmMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    /** One newest message per conversation, for list previews. */
    @Query("""
            select m from CrmMessage m
            where m.conversationId in :conversationIds
              and not exists (
                  select 1 from CrmMessage newer
                  where newer.conversationId = m.conversationId
                    and (newer.createdAt > m.createdAt
                         or (newer.createdAt = m.createdAt and newer.id > m.id))
              )
            """)
    List<CrmMessage> findLatestForConversations(
            @Param("conversationIds") Collection<String> conversationIds);
}
