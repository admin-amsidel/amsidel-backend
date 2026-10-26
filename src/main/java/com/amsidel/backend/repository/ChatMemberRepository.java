package com.amsidel.backend.repository;

import com.amsidel.backend.entity.ChatMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatMemberRepository extends JpaRepository<ChatMember, Long> {

    /** My memberships, with their chats. */
    @Query("select m from ChatMember m join fetch m.conversation where m.employee.id = :employeeId")
    List<ChatMember> mine(@Param("employeeId") Long employeeId);

    /** Everyone in these chats, with their details. */
    @Query("select m from ChatMember m join fetch m.employee e left join fetch e.designation where m.conversation.id in :ids")
    List<ChatMember> inConversations(@Param("ids") Collection<Long> ids);

    Optional<ChatMember> findByConversationIdAndEmployeeId(Long conversationId, Long employeeId);

    @Modifying
    @Query("delete from ChatMember m where m.conversation.id in (select c.id from ChatConversation c"
            + " where c.type = com.amsidel.backend.entity.ChatConversation.Type.DIRECT and c.createdAt < :before"
            + " and not exists (select x.id from ChatMessage x where x.conversation = c))")
    int deleteOfEmptyDirect(@Param("before") Instant before);
}
