package fruition.chat.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "chat_sessions")
public class ChatSession {

    @Id
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    private String title;

    @Column(name = "context_summary", columnDefinition = "TEXT")
    private String contextSummary;

    @Column(name = "context_summary_updated_at")
    private Instant contextSummaryUpdatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "wiki_page_id")
    private String wikiPageId;

    protected ChatSession() {}

    public ChatSession(String id, String workspaceId, String userId, String title) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.title = title;
        this.createdAt = Instant.now();
        this.lastMessageAt = this.createdAt;
    }

    public void touchLastMessageAt(Instant now) {
        this.lastMessageAt = now;
    }

    public String getId() { return id; }
    public String getWorkspaceId() { return workspaceId; }
    public String getUserId() { return userId; }
    public String getTitle() { return title; }
    public String getContextSummary() { return contextSummary; }
    public Instant getContextSummaryUpdatedAt() { return contextSummaryUpdatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastMessageAt() { return lastMessageAt; }
    public String getWikiPageId() { return wikiPageId; }
}
