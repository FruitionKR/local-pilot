package fruition.core.chat.domain;

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

    @Column(name = "wiki_export_document_id")
    private String wikiExportDocumentId;

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

    /**
     * pipeline이 턴마다 갱신해 돌려준 누적 대화 요약을 남긴다.
     * 다음 턴은 원문 대신 이 요약을 맥락으로 읽어, 대화가 길어져도 앞부분이 잘리지 않는다.
     */
    public void updateContextSummary(String summary, Instant now) {
        this.contextSummary = summary;
        this.contextSummaryUpdatedAt = now;
    }

    /** Wiki page화 export 문서 id를 기록한다. 완료 콜백에서 이 세션을 역조회하는 데 쓴다. */
    public void assignWikiExportDocument(String documentId) {
        this.wikiExportDocumentId = documentId;
    }

    /** export 완료 후 생성된 source wiki page를 세션에 연결한다. */
    public void linkWikiPage(String wikiPageId) {
        this.wikiPageId = wikiPageId;
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
    public String getWikiExportDocumentId() { return wikiExportDocumentId; }
}
