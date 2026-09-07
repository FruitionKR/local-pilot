package fruition.access.workspace.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "workspaces")
public class Workspace {

    private static final String DEFAULT_AI_PROVIDER = "gemini";
    private static final String DEFAULT_AI_MODEL = "gemini-3.1-flash-lite";

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private String deletedBy;

    /** 아이콘 이모지. 설정하지 않았으면 null이다. */
    @Column(name = "icon_emoji", length = 32)
    private String iconEmoji;

    @Column(name = "ingest_lint_provider", nullable = false)
    private String ingestLintProvider;

    @Column(name = "ingest_lint_model", nullable = false)
    private String ingestLintModel;

    protected Workspace() {}

    public Workspace(String id, String name) {
        this.id = id;
        this.name = name;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.ingestLintProvider = DEFAULT_AI_PROVIDER;
        this.ingestLintModel = DEFAULT_AI_MODEL;
    }

    public void rename(String name) {
        this.name = name;
        this.updatedAt = Instant.now();
    }

    /** null을 주면 아이콘을 지운다. */
    public void changeIcon(String iconEmoji) {
        this.iconEmoji = iconEmoji;
        this.updatedAt = Instant.now();
    }

    public void softDelete(String userId, Instant deletedAt) {
        this.deletedAt = deletedAt;
        this.deletedBy = userId;
        this.updatedAt = deletedAt;
    }

    public void restore(Instant restoredAt) {
        this.deletedAt = null;
        this.deletedBy = null;
        this.updatedAt = restoredAt;
    }

    public void changeIngestLintModel(String provider, String model) {
        this.ingestLintProvider = provider;
        this.ingestLintModel = model;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getIconEmoji() { return iconEmoji; }
    public Instant getDeletedAt() { return deletedAt; }
    public String getDeletedBy() { return deletedBy; }
    public String getIngestLintProvider() { return ingestLintProvider; }
    public String getIngestLintModel() { return ingestLintModel; }
}
