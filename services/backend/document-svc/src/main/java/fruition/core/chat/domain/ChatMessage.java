package fruition.core.chat.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ChatSession session;

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

    @Column(name = "ai_provider")
    private String aiProvider;

    @Column(name = "ai_model")
    private String aiModel;

    @Column(name = "web_search_enabled")
    private Boolean webSearchEnabled;

    protected ChatMessage() {}

    public ChatMessage(String id, ChatSession session, String pairId, String role, String content,
                       String status, Instant createdAt, String errorMessage) {
        this(id, session, pairId, role, content, status, createdAt, errorMessage, null, null);
    }

    public ChatMessage(String id, ChatSession session, String pairId, String role, String content,
                       String status, Instant createdAt, String errorMessage,
                       String aiProvider, String aiModel) {
        this(id, session, pairId, role, content, status, createdAt, errorMessage,
                aiProvider, aiModel, false);
    }

    public ChatMessage(String id, ChatSession session, String pairId, String role, String content,
                       String status, Instant createdAt, String errorMessage,
                       String aiProvider, String aiModel, boolean webSearchEnabled) {
        this.id = id;
        this.session = session;
        this.pairId = pairId;
        this.role = role;
        this.content = content;
        this.status = status;
        this.createdAt = createdAt;
        this.errorMessage = errorMessage;
        this.aiProvider = aiProvider;
        this.aiModel = aiModel;
        this.webSearchEnabled = webSearchEnabled;
    }

    /** 이 메시지(문답)가 세션 위키 source page에 편입됐음을 기록한다. */
    public void markIngested(String wikiPageId) { this.wikiPageId = wikiPageId; }

    public void complete(String content) {
        this.content = content;
        this.status = "completed";
        this.errorMessage = null;
    }

    public void fail(String errorMessage) {
        this.status = "failed";
        this.errorMessage = errorMessage;
    }

    public String getId() { return id; }
    public String getSessionId() { return session.getId(); }
    public ChatSession getSession() { return session; }
    public String getPairId() { return pairId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getErrorMessage() { return errorMessage; }
    public String getWikiPageId() { return wikiPageId; }
    public String getAiProvider() { return aiProvider; }
    public String getAiModel() { return aiModel; }
    public Boolean getWebSearchEnabled() { return webSearchEnabled; }
}
