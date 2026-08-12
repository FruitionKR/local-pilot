package fruition.core.aihistory.service;

import fruition.core.aihistory.domain.ChangeType;
import fruition.core.aihistory.domain.OperationChange;
import fruition.core.aihistory.domain.OperationLog;
import fruition.core.aihistory.domain.OperationStatus;
import fruition.core.aihistory.domain.OperationType;
import fruition.core.aihistory.domain.ResourceType;
import fruition.core.aihistory.exception.OperationPayloadConflictException;
import fruition.core.aihistory.repository.OperationChangeRepository;
import fruition.core.aihistory.repository.OperationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/** 문서 AI 편집 결과를 AI 작업 로그에 남긴다. */
@Component
public class OperationRecorder {

    private static final Logger log = LoggerFactory.getLogger(OperationRecorder.class);
    private static final String CONFLICT_SUMMARY = "문서가 이미 변경되어 AI 편집을 반영하지 못했습니다.";

    private final OperationLogRepository operationLogRepository;
    private final OperationChangeRepository operationChangeRepository;
    private final LineCounter lineCounter;

    public OperationRecorder(OperationLogRepository operationLogRepository,
                             OperationChangeRepository operationChangeRepository,
                             LineCounter lineCounter) {
        this.operationLogRepository = operationLogRepository;
        this.operationChangeRepository = operationChangeRepository;
        this.lineCounter = lineCounter;
    }

    /**
     * AI 편집을 적용한 결과를 기록한다. <b>문서 저장과 같은 트랜잭션에서</b> 호출해야 한다.
     * 문서만 바뀌고 로그가 없거나 그 반대가 생기지 않는다.
     */
    public void recordDocumentEdit(String operationId, String workspaceId, String userId,
                                   String documentId, long beforeVersion, long afterVersion,
                                   String beforeMarkdown, String afterMarkdown, Instant now) {
        operationLogRepository.save(OperationLog.completed(
                operationId, workspaceId, userId, OperationType.document_edit, documentId,
                "AI 편집을 문서에 반영했습니다.", 1, now));

        LineCounter.LineCount lines = lineCounter.count(
                documentId, beforeVersion, beforeMarkdown, afterVersion, afterMarkdown);
        operationChangeRepository.save(new OperationChange(
                operationId, ResourceType.document, documentId,
                beforeVersion, afterVersion, ChangeType.updated,
                null, lines.additions(), lines.deletions()));
    }

    /**
     * base 버전 불일치로 반영하지 못한 시도를 기록한다.
     *
     * <p>적용 표 소비와 같은 PostgreSQL 트랜잭션에서 호출한다.
     */
    @Transactional
    public void recordConflict(String operationId, String workspaceId, String userId,
                               String documentId, Instant now) {
        if (operationLogRepository.insertConflictIfAbsent(
                operationId, workspaceId, userId, documentId, CONFLICT_SUMMARY, now) == 1) {
            return;
        }

        OperationLog existing = operationLogRepository.findById(operationId)
                .orElseThrow(() -> new IllegalStateException(
                        "충돌 감사 기록을 확인할 수 없습니다: operationId=" + operationId));
        if (!isSameConflict(existing, workspaceId, userId, documentId)) {
            throw new OperationPayloadConflictException(operationId);
        }
    }

    private boolean isSameConflict(OperationLog existing, String workspaceId, String userId,
                                   String documentId) {
        return existing.getStatus() == OperationStatus.conflict
                && existing.getOperationType() == OperationType.document_edit
                && Objects.equals(existing.getWorkspaceId(), workspaceId)
                && Objects.equals(existing.getUserId(), userId)
                && Objects.equals(existing.getTargetDocumentId(), documentId)
                && Objects.equals(existing.getSummary(), CONFLICT_SUMMARY)
                && existing.getChangedResourceCount() == 0
                && existing.getRestoredFrom() == null
                && existing.getRestoreManifest() == null
                && existing.getPayloadHash() == null
                && existing.getCompletedAt() != null;
    }

}
