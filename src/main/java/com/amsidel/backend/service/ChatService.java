package com.amsidel.backend.service;

import com.amsidel.backend.dto.ChatDtos.*;
import com.amsidel.backend.entity.ChatConversation;
import com.amsidel.backend.entity.ChatMember;
import com.amsidel.backend.entity.ChatMessage;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.ChatConversationRepository;
import com.amsidel.backend.repository.ChatMemberRepository;
import com.amsidel.backend.repository.ChatMessageRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.security.AuthUser;
import com.amsidel.backend.security.Menus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Team chat: one-to-one and group chats between people given the Team chat checkpoint. The chat window asks for news
 * every few seconds (sync): my chats with their last message and unread count, new messages, who is typing and who is
 * online. Typing and presence live in memory (one server); messages are kept for app.chat.retention-days (ChatCleanup).
 */
@Service
public class ChatService {

    /** Online = the chat window asked within this time. */
    private static final Duration ONLINE = Duration.ofSeconds(45);
    private static final Duration TYPING = Duration.ofSeconds(6);

    private final ChatConversationRepository conversations;
    private final ChatMemberRepository members;
    private final ChatMessageRepository messages;
    private final EmployeeRepository employees;
    private final MenuAccessService menuAccess;
    private final int retentionDays;

    private final Map<Long, Instant> lastSeen = new ConcurrentHashMap<>();
    /** conversation -> (employee -> typing until). */
    private final Map<Long, Map<Long, Instant>> typing = new ConcurrentHashMap<>();
    private final Map<Long, String> names = new ConcurrentHashMap<>();

    public ChatService(ChatConversationRepository conversations, ChatMemberRepository members, ChatMessageRepository messages,
                       EmployeeRepository employees, MenuAccessService menuAccess, @Value("${app.chat.retention-days:7}") int retentionDays) {
        this.conversations = conversations;
        this.members = members;
        this.messages = messages;
        this.employees = employees;
        this.menuAccess = menuAccess;
        this.retentionDays = retentionDays;
    }

    private void canChat(AuthUser me) {
        if (!menuAccess.can(me, Menus.CHAT)) {
            throw ApiException.forbidden("Team chat is not open to you; ask your admin for the Team chat access");
        }
    }

    /** May this person be in a chat: active, with the Team chat checkpoint. */
    private boolean chats(Employee e) {
        return e.isActive() && menuAccess.can(AuthUser.of(e), Menus.CHAT);
    }

    private boolean online(Long id) {
        Instant t = lastSeen.get(id);
        return t != null && t.isAfter(Instant.now().minus(ONLINE));
    }

    // ---- people ----

    /** Everyone I can start a chat with. */
    @Transactional(readOnly = true)
    public List<Person> people(AuthUser me) {
        canChat(me);
        return employees.search(null).stream().filter(e -> !e.getId().equals(me.id()) && chats(e)).map(this::person).toList();
    }

    private Person person(Employee e) {
        names.put(e.getId(), e.getName());
        return new Person(e.getId(), e.getName(), e.getDesignation() == null ? null : e.getDesignation().getName(),
                e.getBrand() == null ? null : e.getBrand().getCode(), e.getPhotoUpdatedAt(), online(e.getId()));
    }

    // ---- the window's poll ----

    @Transactional(readOnly = true)
    public Sync sync(AuthUser me, long after) {
        canChat(me);
        lastSeen.put(me.id(), Instant.now());
        List<ConversationView> list = conversationsOf(me);
        Set<Long> ids = list.stream().map(ConversationView::id).collect(Collectors.toSet());
        // after < 0: the window's first look (it loads a chat when opened); otherwise everything newer than it has
        List<MessageView> fresh = after < 0 || ids.isEmpty() ? List.of()
                : messages.after(ids, after, PageRequest.of(0, 300)).stream().map(ChatService::view).toList();
        Instant now = Instant.now();
        Map<Long, List<String>> typingNow = new HashMap<>();
        for (Long id : ids) {
            Map<Long, Instant> t = typing.get(id);
            if (t == null) {
                continue;
            }
            List<String> who = t.entrySet().stream().filter(x -> !x.getKey().equals(me.id()) && x.getValue().isAfter(now))
                    .map(x -> names.getOrDefault(x.getKey(), "Someone")).toList();
            if (!who.isEmpty()) {
                typingNow.put(id, who);
            }
        }
        List<Long> onlineNow = lastSeen.entrySet().stream().filter(x -> x.getValue().isAfter(now.minus(ONLINE))).map(Map.Entry::getKey).toList();
        int unread = list.stream().mapToInt(ConversationView::unread).sum();
        return new Sync(list, fresh, typingNow, onlineNow, unread, retentionDays, now);
    }

    /** My chats, newest activity first. */
    private List<ConversationView> conversationsOf(AuthUser me) {
        List<ChatMember> mine = members.mine(me.id());
        if (mine.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = mine.stream().map(m -> m.getConversation().getId()).collect(Collectors.toSet());
        Map<Long, List<ChatMember>> people = members.inConversations(ids).stream()
                .collect(Collectors.groupingBy(m -> m.getConversation().getId()));
        Map<Long, ChatMessage> last = messages.lastOf(ids).stream().collect(Collectors.toMap(m -> m.getConversation().getId(), m -> m));
        Map<Long, Integer> unread = new HashMap<>();
        for (Object[] row : messages.unread(me.id())) {
            unread.put((Long) row[0], ((Number) row[1]).intValue());
        }
        List<ConversationView> out = new ArrayList<>();
        for (ChatMember m : mine) {
            ChatConversation c = m.getConversation();
            List<ChatMember> all = people.getOrDefault(c.getId(), List.of());
            out.add(view(me, c, m, all, last.get(c.getId()), unread.getOrDefault(c.getId(), 0)));
        }
        out.sort(Comparator.comparing((ConversationView v) -> v.lastMessageAt() == null ? Instant.EPOCH : v.lastMessageAt()).reversed()
                .thenComparing(ConversationView::id, Comparator.reverseOrder()));
        return out;
    }

    private ConversationView view(AuthUser me, ChatConversation c, ChatMember mine, List<ChatMember> all, ChatMessage last, int unread) {
        List<Person> people = all.stream().map(x -> person(x.getEmployee())).sorted(Comparator.comparing(Person::name, String.CASE_INSENSITIVE_ORDER)).toList();
        List<ChatMember> others = all.stream().filter(x -> !x.getEmployee().getId().equals(me.id())).toList();
        long othersRead = others.isEmpty() ? 0 : others.stream().mapToLong(ChatMember::getLastReadMessageId).min().orElse(0);
        String title = c.getType() == ChatConversation.Type.GROUP ? c.getName()
                : others.isEmpty() ? "Just you" : others.getFirst().getEmployee().getName();
        return new ConversationView(c.getId(), c.getType().name(), title, c.getCreatedBy().getId(), people, last == null ? null : view(last),
                unread, mine.getLastReadMessageId(), othersRead, c.getLastMessageAt() != null ? c.getLastMessageAt() : c.getCreatedAt());
    }

    private static MessageView view(ChatMessage m) {
        return new MessageView(m.getId(), m.getConversation().getId(), m.getSender().getId(), m.getSender().getName(), m.getContent(), m.getCreatedAt());
    }

    private ChatMember member(AuthUser me, Long conversationId) {
        return members.findByConversationIdAndEmployeeId(conversationId, me.id())
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found: " + conversationId));
    }

    private ConversationView viewOf(AuthUser me, Long conversationId) {
        return conversationsOf(me).stream().filter(v -> v.id().equals(conversationId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found: " + conversationId));
    }

    // ---- messages ----

    /** A page of a chat, oldest first (before = the oldest message already shown). */
    @Transactional(readOnly = true)
    public List<MessageView> messages(AuthUser me, Long conversationId, Long before, int size) {
        canChat(me);
        member(me, conversationId);
        List<MessageView> page = new ArrayList<>(messages.page(conversationId, before, PageRequest.of(0, Math.clamp(size, 1, 100)))
                .stream().map(ChatService::view).toList());
        Collections.reverse(page);
        return page;
    }

    @Transactional
    public MessageView send(AuthUser me, Long conversationId, SendBody body) {
        canChat(me);
        ChatMember mine = member(me, conversationId);
        String text = body.getContent().strip();
        if (text.isEmpty()) {
            throw ApiException.badRequest("Type a message");
        }
        ChatMessage m = new ChatMessage();
        m.setConversation(mine.getConversation());
        m.setSender(mine.getEmployee());
        m.setContent(text);
        m.setCreatedAt(Instant.now());
        messages.save(m);
        mine.getConversation().setLastMessageAt(m.getCreatedAt());
        mine.setLastReadMessageId(m.getId());
        Map<Long, Instant> t = typing.get(conversationId);
        if (t != null) {
            t.remove(me.id());
        }
        lastSeen.put(me.id(), Instant.now());
        return view(m);
    }

    /** I have seen the chat up to this message. */
    @Transactional
    public void read(AuthUser me, Long conversationId, long messageId) {
        canChat(me);
        ChatMember mine = member(me, conversationId);
        long upTo = Math.min(messageId, messages.lastId(conversationId));
        if (upTo > mine.getLastReadMessageId()) {
            mine.setLastReadMessageId(upTo);
        }
    }

    public void typing(AuthUser me, Long conversationId, boolean on) {
        canChat(me);
        members.findByConversationIdAndEmployeeId(conversationId, me.id()).ifPresent(m -> {
            Map<Long, Instant> t = typing.computeIfAbsent(conversationId, k -> new ConcurrentHashMap<>());
            if (on) {
                t.put(me.id(), Instant.now().plus(TYPING));
            } else {
                t.remove(me.id());
            }
        });
    }

    // ---- starting chats ----

    /** The one-to-one chat with this person (made the first time). */
    @Transactional
    public ConversationView direct(AuthUser me, Long employeeId) {
        canChat(me);
        if (employeeId.equals(me.id())) {
            throw ApiException.badRequest("Choose someone else");
        }
        Employee other = employees.findById(employeeId).filter(this::chats)
                .orElseThrow(() -> ApiException.badRequest("That person does not have Team chat"));
        String key = Math.min(me.id(), employeeId) + ":" + Math.max(me.id(), employeeId);
        ChatConversation c = conversations.findByDirectKey(key).orElse(null);
        if (c == null) {
            c = new ChatConversation();
            c.setType(ChatConversation.Type.DIRECT);
            c.setDirectKey(key);
            c.setCreatedBy(employees.getReferenceById(me.id()));
            c.setCreatedAt(Instant.now());
            conversations.save(c);
            join(c, employees.getReferenceById(me.id()));
            join(c, other);
        }
        conversations.flush();
        return viewOf(me, c.getId());
    }

    @Transactional
    public ConversationView group(AuthUser me, GroupBody body) {
        canChat(me);
        ChatConversation c = new ChatConversation();
        c.setType(ChatConversation.Type.GROUP);
        c.setName(body.getName().strip());
        c.setCreatedBy(employees.getReferenceById(me.id()));
        c.setCreatedAt(Instant.now());
        conversations.save(c);
        join(c, employees.getReferenceById(me.id()));
        for (Employee e : chatters(me, body.getMemberIds())) {
            join(c, e);
        }
        members.flush();
        return viewOf(me, c.getId());
    }

    /** Anyone in a group adds people to it. */
    @Transactional
    public ConversationView addMembers(AuthUser me, Long conversationId, MembersBody body) {
        canChat(me);
        ChatConversation c = member(me, conversationId).getConversation();
        if (c.getType() != ChatConversation.Type.GROUP) {
            throw ApiException.badRequest("People are added to groups only; start a group instead");
        }
        for (Employee e : chatters(me, body.getMemberIds())) {
            if (members.findByConversationIdAndEmployeeId(conversationId, e.getId()).isEmpty()) {
                join(c, e);
            }
        }
        members.flush();
        return viewOf(me, conversationId);
    }

    /** I leave a group; the last one out removes it. */
    @Transactional
    public void leave(AuthUser me, Long conversationId) {
        canChat(me);
        ChatMember mine = member(me, conversationId);
        ChatConversation c = mine.getConversation();
        if (c.getType() != ChatConversation.Type.GROUP) {
            throw ApiException.badRequest("A one-to-one chat cannot be left");
        }
        members.delete(mine);
        members.flush();
        if (members.inConversations(List.of(conversationId)).isEmpty()) {
            conversations.delete(c);
        }
    }

    private List<Employee> chatters(AuthUser me, List<Long> ids) {
        List<Employee> found = employees.findAllById(new LinkedHashSet<>(ids)).stream().filter(e -> !e.getId().equals(me.id())).toList();
        List<String> cannot = found.stream().filter(e -> !chats(e)).map(Employee::getName).toList();
        if (!cannot.isEmpty()) {
            throw ApiException.badRequest(String.join(", ", cannot) + (cannot.size() == 1 ? " does" : " do") + " not have Team chat");
        }
        if (found.isEmpty()) {
            throw ApiException.badRequest("Add at least one other person");
        }
        return found;
    }

    private void join(ChatConversation c, Employee e) {
        ChatMember m = new ChatMember();
        m.setConversation(c);
        m.setEmployee(e);
        m.setJoinedAt(Instant.now());
        m.setLastReadMessageId(c.getId() == null ? 0 : messages.lastId(c.getId()));
        members.save(m);
    }

    public int retentionDays() {
        return retentionDays;
    }
}
