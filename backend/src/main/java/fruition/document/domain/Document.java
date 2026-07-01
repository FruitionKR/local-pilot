package fruition.document.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "documents")
public class Document {

    @Id
    private String id;

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

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "pipeline_run_id")
    private String pipelineRunId;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "processing_updated_at")
    private Instant processingUpdatedAt;

    protected Document() {}

    public Document(String id, String filename, String mimeType, long byteSize,
                    String sourceUri, String contentHash) {
        this.id = id;
        this.filename = filename;
        this.mimeType = mimeType;
        this.byteSize = byteSize;
        this.status = DocumentStatus.processing;
        this.sourceUri = sourceUri;
        this.contentHash = contentHash;
        this.uploadedAt = Instant.now();
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
}
