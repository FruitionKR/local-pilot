package fruition.core.document.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "document_processing_queue")
public class DocumentProcessingQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false, unique = true)
    private String documentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private String status;

    protected DocumentProcessingQueue() {}

    public DocumentProcessingQueue(String documentId) {
        this.documentId = documentId;
        this.createdAt = Instant.now();
        this.status = "pending";
    }

    public Long getId() { return id; }
    public String getDocumentId() { return documentId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
