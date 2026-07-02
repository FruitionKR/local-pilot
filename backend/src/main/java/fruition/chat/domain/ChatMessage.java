package fruition.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    private String id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "pair_id", nullable = false)
    private String pairId;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "error_message", length = 255)
    private String errorMessage;

    @Column(name = "wiki_page_id")
    private String wikiPageId;

    protected ChatMessage() {}

    public ChatMessage(String id, String sessionId, String pairId, String role, String content,
                       String status, Instant createdAt, String errorMessage) {
        this.id = id;
        this.sessionId = sessionId;
        this.pairId = pairId;
        this.role = role;
        this.content = content;
        this.status = status;
        this.createdAt = createdAt;
        this.errorMessage = errorMessage;
    }

    public String getId() { return id; }
    public String getSessionId() { return sessionId; }
    public String getPairId() { return pairId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getErrorMessage() { return errorMessage; }
    public String getWikiPageId() { return wikiPageId; }
}
