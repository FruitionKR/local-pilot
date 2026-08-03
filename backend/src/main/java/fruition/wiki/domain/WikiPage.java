package fruition.wiki.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "wiki_pages",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_wiki_pages_workspace_type_slug",
        columnNames = {"user_id", "workspace_id", "page_type", "slug"}
    )
)
public class WikiPage {

    @Id
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "page_type", nullable = false)
    private WikiPageType pageType;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "markdown_uri")
    private String markdownUri;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WikiPageStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WikiPage() {}

    public WikiPage(String id, WikiPageType pageType, String title, String slug,
                    String summary, String markdownUri) {
        this.id = id;
        this.pageType = pageType;
        this.title = title;
        this.slug = slug;
        this.summary = summary;
        this.markdownUri = markdownUri;
        this.status = WikiPageStatus.draft;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void activate() {
        this.status = WikiPageStatus.active;
        this.updatedAt = Instant.now();
    }

    public void markFailed() {
        this.status = WikiPageStatus.failed;
        this.updatedAt = Instant.now();
    }

    public void updateContent(String title, String summary, String markdownUri) {
        this.title = title;
        this.summary = summary;
        this.markdownUri = markdownUri;
        this.updatedAt = Instant.now();
    }

    public void renameTitle(String title) {
        this.title = title;
        this.updatedAt = Instant.now();
    }

    public void updateSlug(String slug) {
        this.slug = slug;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public WikiPageType getPageType() { return pageType; }
    public String getTitle() { return title; }
    public String getSlug() { return slug; }
    public String getSummary() { return summary; }
    public String getMarkdownUri() { return markdownUri; }
    public String getUserId() { return userId; }
    public String getWorkspaceId() { return workspaceId; }
    public WikiPageStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
