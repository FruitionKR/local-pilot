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

    protected ChatMessage() {}

    public ChatMessage(String id, String role, String content, String status, Instant createdAt, String errorMessage) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.status = status;
        this.createdAt = createdAt;
        this.errorMessage = errorMessage;
    }

    public String getId() { return id; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getErrorMessage() { return errorMessage; }
}
