package com.yeosal.api.room.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Models {@code chat_messages} from V7. {@code roomId} and {@code senderUserId}
 * are kept as scalar columns rather than JPA associations so the entity stays
 * cheap to construct in tests and the system-message hooks (PR G) don't have
 * to resolve a {@link com.yeosal.api.user.User} just to write a row.
 *
 * <p>{@code payload} is a JSONB column. Hibernate 6 {@link JdbcTypeCode} casts
 * the {@code String} value to JSONB on insert. PR F only writes {@code "{}"};
 * PR G writes structured metadata such as {@code {"actorUserId":7,"date":...}}.
 */
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(name = "sender_user_id")
    private Long senderUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatMessageKind kind;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode payload = JsonNodeFactory.instance.objectNode();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChatMessage() {}

    public ChatMessage(long roomId, Long senderUserId, ChatMessageKind kind, String body) {
        this.roomId = roomId;
        this.senderUserId = senderUserId;
        this.kind = kind;
        this.body = body;
    }

    public ChatMessage(long roomId, Long senderUserId, ChatMessageKind kind, String body, JsonNode payload) {
        this(roomId, senderUserId, kind, body);
        if (payload != null) {
            this.payload = payload;
        }
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (payload == null) {
            payload = JsonNodeFactory.instance.objectNode();
        }
    }

    public Long getId() { return id; }
    public Long getRoomId() { return roomId; }
    public Long getSenderUserId() { return senderUserId; }
    public ChatMessageKind getKind() { return kind; }
    public String getBody() { return body; }
    public JsonNode getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
}
