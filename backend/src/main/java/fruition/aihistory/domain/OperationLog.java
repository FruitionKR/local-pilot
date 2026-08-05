package fruition.aihistory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * AI 작업 1회의 기록. 작업 유형과 무관하게 이 테이블 한 곳에 모인다.
 *
 * <p>ingest는 llmPipeline 호출 <b>전에</b> {@link OperationStatus#processing}으로 먼저 커밋하고,
 * 콜백을 받아 확정한다. 문서 AI 편집은 동기라 저장과 같은 트랜잭션에서 한 번에 기록한다.
 */
@Entity
@Table(name = "ai_operation_logs")
public class OperationLog {

    @Id
    @Column(name = "operation_id")
    private String operationId;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 20)
    private OperationType operationType;

    /** 어느 원문 문서의 작업인지. 복구 대상 선정의 근거이며 lint는 NULL이다. */
    @Column(name = "target_document_id")
    private String targetDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OperationStatus status;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "changed_resource_count", nullable = false)
    private int changedResourceCount;

    /** 복구 작업이 되돌린 대상 작업. */
    @Column(name = "restored_from")
    private String restoredFrom;

    /** 복구 시 llmPipeline에 보낸 조립 지시서 원본. 재조립 결과 수신 때 목표값을 여기서 꺼낸다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "restore_manifest")
    private String restoreManifest;

    /** 완료 콜백 payload의 정규화 해시. 같은 payload 재전송과 다른 payload를 가른다. */
    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected OperationLog() {}

    private OperationLog(String operationId, String workspaceId, String userId,
                         OperationType operationType, String targetDocumentId,
                         OperationStatus status, Instant createdAt) {
        this.operationId = operationId;
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.operationType = operationType;
        this.targetDocumentId = targetDocumentId;
        this.status = status;
        this.changedResourceCount = 0;
        this.createdAt = createdAt;
    }

    /** llmPipeline 호출 전에 먼저 커밋하는 진행 중 작업. */
    public static OperationLog processing(String operationId, String workspaceId, String userId,
                                          OperationType operationType, String targetDocumentId,
                                          Instant createdAt) {
        return new OperationLog(operationId, workspaceId, userId, operationType, targetDocumentId,
                OperationStatus.processing, createdAt);
    }

    /** 동기 처리라 시작과 동시에 끝나는 작업(문서 AI 편집). */
    public static OperationLog completed(String operationId, String workspaceId, String userId,
                                         OperationType operationType, String targetDocumentId,
                                         String summary, int changedResourceCount, Instant now) {
        OperationLog log = new OperationLog(operationId, workspaceId, userId, operationType,
                targetDocumentId, OperationStatus.succeeded, now);
        log.summary = summary;
        log.changedResourceCount = changedResourceCount;
        log.completedAt = now;
        return log;
    }

    /** 복구 실행을 시작한다. 지시서 원본을 함께 보관해 재조립 수신과 재시도에 사용한다. */
    public static OperationLog applying(String operationId, String workspaceId, String userId,
                                        String targetDocumentId, String restoredFrom,
                                        String restoreManifest, Instant createdAt) {
        OperationLog log = new OperationLog(operationId, workspaceId, userId, OperationType.restore,
                targetDocumentId, OperationStatus.applying, createdAt);
        log.restoredFrom = restoredFrom;
        log.restoreManifest = restoreManifest;
        return log;
    }

    /** 결과를 확정한다. {@code changedResourceCount}는 실제로 만든 변경내역 수다. */
    public void complete(OperationStatus status, String summary, int changedResourceCount,
                         String payloadHash, Instant completedAt) {
        this.status = status;
        this.summary = summary;
        this.changedResourceCount = changedResourceCount;
        this.payloadHash = payloadHash;
        this.completedAt = completedAt;
    }

    /** 아직 끝나지 않은 중간 상태로 옮긴다(복구의 notify_pending, rebuilding). */
    public void moveTo(OperationStatus status) {
        this.status = status;
    }

    /**
     * 중간 상태로 옮기면서 요약을 남긴다. 결과를 기다리는 동안에도 목록에 무엇을 했는지 보여야 한다.
     */
    public void moveTo(OperationStatus status, String summary) {
        this.status = status;
        this.summary = summary;
    }

    public String getOperationId() { return operationId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getUserId() { return userId; }
    public OperationType getOperationType() { return operationType; }
    public String getTargetDocumentId() { return targetDocumentId; }
    public OperationStatus getStatus() { return status; }
    public String getSummary() { return summary; }
    public int getChangedResourceCount() { return changedResourceCount; }
    public String getRestoredFrom() { return restoredFrom; }
    public String getRestoreManifest() { return restoreManifest; }
    public String getPayloadHash() { return payloadHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
