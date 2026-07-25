package zelisline.ub.messages.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.messages.domain.ContactMessageReply;

public interface ContactMessageReplyRepository extends JpaRepository<ContactMessageReply, String> {

    List<ContactMessageReply> findByContactMessageIdOrderByCreatedAtAsc(String contactMessageId);
}
