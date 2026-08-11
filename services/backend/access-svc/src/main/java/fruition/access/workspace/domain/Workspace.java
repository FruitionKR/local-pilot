package fruition.access.workspace.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "workspaces")
public class Workspace {

    private static final String DEFAULT_AI_PROVIDER = "openai";
    private static final String DEFAULT_AI_MODEL = "gpt-5-nano";

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
    public Instant getDeletedAt() { return deletedAt; }
    public String getDeletedBy() { return deletedBy; }
    public String getIngestLintProvider() { return ingestLintProvider; }
    public String getIngestLintModel() { return ingestLintModel; }
}
