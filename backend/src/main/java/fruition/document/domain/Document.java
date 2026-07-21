package fruition.document.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import java.time.Instant;

@Entity
// 파이프라인이 raw SQL로 같은 documents 행에 직접 쓰므로, backend는 변경한 컬럼만 UPDATE해 파이프라인 컬럼을 덮어쓰지 않는다.
@DynamicUpdate
@Table(name = "documents",
        indexes = @Index(name = "idx_documents_reconcile", columnList = "origin, status, reconciled_at"),
        uniqueConstraints = @UniqueConstraint(name = "uq_documents_workspace_content_hash",
                columnNames = {"workspace_id", "content_hash"}))
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

    @Column(name = "content_hash", nullable = false)
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

    /** 파이프라인이 heartbeat로 보내는 현재 처리 단계 라벨(예: "5. Source Page 생성"). */
    @Column(name = "processing_stage")
    private String processingStage;

    /** 문서 출처. 일반 업로드는 "upload", 채팅 Wiki page화 export는 "chat_export". */
    @Column(name = "origin")
    private String origin;

    /** 채팅 export의 선택 모드("full"/"partial"). 파이프라인에 전달한다. 일반 업로드는 null. */
    @Column(name = "selection_mode")
    private String selectionMode;

    /**
     * full 재생성 시 파이프라인에 inline으로 보낼 미편입 문답(delta) Markdown. 있으면 storage 대신 이 값을 input_markdown으로 전달한다.
     * 일반 업로드·첫 export는 null(= document_id + storage 경로).
     */
    @Column(name = "pipeline_input_markdown", columnDefinition = "TEXT")
    private String pipelineInputMarkdown;

    /** 채팅 export 완료 후처리(reconcile) 완료 시각. null이면 아직 후처리 안 됨(=폴링 대상). 재생성 시 다시 null로 리셋된다. */
    @Column(name = "reconciled_at")
    private Instant reconciledAt;

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

    public void markProcessingHeartbeat(String stage, Instant now) {
        if (stage != null && !stage.isBlank()) {
            this.processingStage = stage;
        }
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

    /**
     * 채팅 full 재생성: 기존 export 문서를 갱신해 재처리한다. MinIO 원본은 세션 전체로 덮어쓴 뒤,
     * 그 전체 내용의 해시/크기로 갱신하고, 파이프라인엔 delta만 inline으로 보낸다.
     */
    public void reopenForChatExportRegeneration(String contentHash, long byteSize, String pipelineInputMarkdown) {
        this.contentHash = contentHash;
        this.byteSize = byteSize;
        this.status = DocumentStatus.processing;
        this.processedAt = null;
        this.errorMessage = null;
        this.pipelineInputMarkdown = pipelineInputMarkdown;
        this.reconciledAt = null; // 재처리하므로 완료 후 다시 reconcile 대상이 되게 리셋
    }

    /** 완료 후처리(reconcile)를 마쳤음을 기록한다. 이후 폴링 조회에서 제외된다. */
    public void markReconciled(Instant now) {
        this.reconciledAt = now;
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
    public String getProcessingStage() { return processingStage; }
    public String getOrigin() { return origin; }

    public void assignSelectionMode(String selectionMode) { this.selectionMode = selectionMode; }
    public String getSelectionMode() { return selectionMode; }
    public String getPipelineInputMarkdown() { return pipelineInputMarkdown; }
    public Instant getReconciledAt() { return reconciledAt; }
}
