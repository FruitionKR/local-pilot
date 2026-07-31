package fruition.aihistory.service;

import fruition.aihistory.domain.ChangeType;
import fruition.aihistory.domain.OperationChange;
import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.OperationStatus;
import fruition.aihistory.domain.OperationType;
import fruition.aihistory.domain.ResourceType;
import fruition.aihistory.repository.OperationChangeRepository;
import fruition.aihistory.repository.OperationLogRepository;
import fruition.document.dto.DocumentContentDiffResponse;
import fruition.document.service.MarkdownDiffService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 문서 AI 편집 결과를 AI 작업 로그에 남긴다. */
@Component
public class OperationRecorder {

    private static final Logger log = LoggerFactory.getLogger(OperationRecorder.class);

    private final OperationLogRepository operationLogRepository;
    private final OperationChangeRepository operationChangeRepository;
    private final MarkdownDiffService markdownDiffService;

    public OperationRecorder(OperationLogRepository operationLogRepository,
                             OperationChangeRepository operationChangeRepository,
                             MarkdownDiffService markdownDiffService) {
        this.operationLogRepository = operationLogRepository;
        this.operationChangeRepository = operationChangeRepository;
        this.markdownDiffService = markdownDiffService;
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

        LineCount lines = countLines(documentId, beforeVersion, beforeMarkdown, afterVersion, afterMarkdown);
        operationChangeRepository.save(new OperationChange(
                operationId, ResourceType.document, documentId,
                beforeVersion, afterVersion, ChangeType.updated,
                null, lines.additions(), lines.deletions()));
    }

    /**
     * base 버전 불일치로 반영하지 못한 시도를 기록한다.
     *
     * <p>본 트랜잭션은 롤백되므로 <b>별도 트랜잭션</b>으로 커밋해야 로그가 남는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordConflict(String operationId, String workspaceId, String userId,
                               String documentId, Instant now) {
        OperationLog conflict = OperationLog.completed(
                operationId, workspaceId, userId, OperationType.document_edit, documentId,
                "문서가 이미 변경되어 AI 편집을 반영하지 못했습니다.", 0, now);
        conflict.complete(OperationStatus.conflict, conflict.getSummary(), 0, null, now);
        operationLogRepository.save(conflict);
    }

    /**
     * 줄 수 계산이 실패해도 저장을 막지 않는다. 큰 문서는 diff 계산이 거부될 수 있는데,
     * 로그 때문에 사용자 저장이 실패하는 것은 잘못된 트레이드오프다.
     */
    private LineCount countLines(String documentId, long beforeVersion, String beforeMarkdown,
                                 long afterVersion, String afterMarkdown) {
        try {
            DocumentContentDiffResponse diff = markdownDiffService.compare(
                    documentId, beforeVersion, beforeMarkdown, afterVersion, afterMarkdown);
            return new LineCount(diff.additions(), diff.deletions());
        } catch (RuntimeException e) {
            log.warn("[AI 편집 줄 수 계산 생략] documentId={} reason={}", documentId, e.getMessage());
            return new LineCount(null, null);
        }
    }

    private record LineCount(Integer additions, Integer deletions) {}
}
