package com.amsidel.backend.repository;

import com.amsidel.backend.entity.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

    Optional<ChatConversation> findByDirectKey(String directKey);

    /** One-to-one chats left with no message after the clean-up (groups stay). */
    @Modifying
    @Query("delete from ChatConversation c where c.type = com.amsidel.backend.entity.ChatConversation.Type.DIRECT"
            + " and c.createdAt < :before and not exists (select m.id from ChatMessage m where m.conversation = c)")
    int deleteEmptyDirect(@Param("before") Instant before);
}
