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

    /** 아이콘 이미지 metadata. 바이너리는 {@link WorkspaceIcon}에 둔다. 이모지와 배타적이다. */
    @Column(name = "icon_image_content_type", length = 64)
    private String iconImageContentType;

    /** 이미지 SHA-256. 아이콘 유무 판정과 서빙 ETag로 쓴다. */
    @Column(name = "icon_image_hash", length = 64)
    private String iconImageHash;

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

    /** 이모지로 설정한다. null을 주면 아이콘을 지운다. 이미지가 있었다면 함께 사라진다. */
    public void changeIcon(String iconEmoji) {
        this.iconEmoji = iconEmoji;
        clearIconImage();
        this.updatedAt = Instant.now();
    }

    /** 이미지로 설정한다. 이모지가 있었다면 함께 사라진다. 바이너리 저장은 호출자가 한다. */
    public void changeIconImage(String contentType, String hash) {
        this.iconEmoji = null;
        this.iconImageContentType = contentType;
        this.iconImageHash = hash;
        this.updatedAt = Instant.now();
    }

    public boolean hasIconImage() {
        return iconImageHash != null;
    }

    private void clearIconImage() {
        this.iconImageContentType = null;
        this.iconImageHash = null;
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
    public String getIconImageContentType() { return iconImageContentType; }
    public String getIconImageHash() { return iconImageHash; }
    public Instant getDeletedAt() { return deletedAt; }
    public String getDeletedBy() { return deletedBy; }
    public String getIngestLintProvider() { return ingestLintProvider; }
    public String getIngestLintModel() { return ingestLintModel; }
}
