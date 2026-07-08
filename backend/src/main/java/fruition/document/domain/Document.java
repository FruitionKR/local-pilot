package fruition.document.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "documents")
public class Document {

    @Id
    private String id;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String filename;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status;

    @Column(name = "source_uri", nullable = false)
    private String sourceUri;

    @Column(name = "extracted_text_uri")
    private String extractedTextUri;

    @Column(name = "content_hash", nullable = false, unique = true)
    private String contentHash;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "pipeline_run_id")
    private String pipelineRunId;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "processing_updated_at")
    private Instant processingUpdatedAt;

    /** 문서 출처. 일반 업로드는 "upload", 채팅 Wiki page화 export는 "chat_export". */
    @Column(name = "origin")
    private String origin;

    /** 채팅 export의 선택 모드("full"/"partial"). 파이프라인에 전달한다. 일반 업로드는 null. */
    @Column(name = "selection_mode")
    private String selectionMode;

    protected Document() {}

    public Document(String id, String workspaceId, String userId, String filename, String mimeType, long byteSize,
                    String sourceUri, String contentHash) {
        this(id, workspaceId, userId, filename, mimeType, byteSize, sourceUri, contentHash, "upload");
    }

    public Document(String id, String workspaceId, String userId, String filename, String mimeType, long byteSize,
                    String sourceUri, String contentHash, String origin) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.filename = filename;
        this.mimeType = mimeType;
        this.byteSize = byteSize;
        this.status = DocumentStatus.processing;
        this.sourceUri = sourceUri;
        this.contentHash = contentHash;
        this.uploadedAt = Instant.now();
        this.origin = origin;
    }

    public void updateStatus(DocumentStatus status, String extractedTextUri,
                             Instant processedAt, String errorMessage) {
        this.status = status;
        this.extractedTextUri = extractedTextUri;
        this.processedAt = processedAt;
        this.errorMessage = errorMessage;
    }

    public void markPipelineStarted(String pipelineRunId, Instant now) {
        this.pipelineRunId = pipelineRunId;
        this.processingStartedAt = now;
        this.processingUpdatedAt = now;
    }

    public void markProcessingHeartbeat(Instant now) {
        this.processingUpdatedAt = now;
    }

    public void markProcessingFailed(String errorMessage, Instant now) {
        this.status = DocumentStatus.failed;
        this.errorMessage = errorMessage;
        this.processedAt = now;
        this.processingUpdatedAt = now;
    }

    public void rename(String filename) {
        this.filename = filename;
    }

    public String getId() { return id; }
    public String getWorkspaceId() { return workspaceId; }
    public String getUserId() { return userId; }
    public String getFilename() { return filename; }
    public String getMimeType() { return mimeType; }
    public long getByteSize() { return byteSize; }
    public DocumentStatus getStatus() { return status; }
    public String getSourceUri() { return sourceUri; }
    public String getExtractedTextUri() { return extractedTextUri; }
    public String getContentHash() { return contentHash; }
    public Instant getUploadedAt() { return uploadedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public String getErrorMessage() { return errorMessage; }
    public String getPipelineRunId() { return pipelineRunId; }
    public Instant getProcessingStartedAt() { return processingStartedAt; }
    public Instant getProcessingUpdatedAt() { return processingUpdatedAt; }
    public String getOrigin() { return origin; }

    public void assignSelectionMode(String selectionMode) { this.selectionMode = selectionMode; }
    public String getSelectionMode() { return selectionMode; }
}
