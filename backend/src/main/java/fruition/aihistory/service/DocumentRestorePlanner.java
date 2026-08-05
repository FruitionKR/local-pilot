package fruition.aihistory.service;

import fruition.aihistory.domain.OperationChange;
import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.ResourceType;
import fruition.aihistory.dto.DocumentRestorePlan;
import fruition.aihistory.exception.InvalidRestoreRequestException;
import fruition.aihistory.repository.OperationChangeRepository;
import fruition.document.domain.Document;
import fruition.document.exception.DocumentNotFoundException;
import fruition.document.repository.DocumentRepository;
import org.springframework.stereotype.Component;

/**
 * 문서 편집 되돌리기 계획. Wiki와 달리 계산할 것이 없다.
 *
 * <p>문서 버전은 선형이라 "그 작업이 손대기 직전 버전"이 곧 목적지다. 되돌릴 지점은
 * 이미 {@code ai_operation_changes.before_revision}에 적혀 있다.
 */
@Component
public class DocumentRestorePlanner {

    private final OperationChangeRepository operationChangeRepository;
    private final DocumentRepository documentRepository;

    public DocumentRestorePlanner(OperationChangeRepository operationChangeRepository,
                                  DocumentRepository documentRepository) {
        this.operationChangeRepository = operationChangeRepository;
        this.documentRepository = documentRepository;
    }

    public DocumentRestorePlan plan(OperationLog target) {
        OperationChange change = operationChangeRepository
                .findByOperationIdOrderByIdAsc(target.getOperationId()).stream()
                .filter(c -> c.getResourceType() == ResourceType.document)
                .findFirst()
                .orElseThrow(() -> new InvalidRestoreRequestException(
                        "되돌릴 문서 변경내역이 없습니다: operationId=" + target.getOperationId()));

        // 새로 만든 문서라면 돌아갈 지점이 없다.
        if (change.getBeforeRevision() == null) {
            throw new InvalidRestoreRequestException(
                    "되돌릴 이전 버전이 없는 작업입니다: operationId=" + target.getOperationId());
        }

        String documentId = change.getResourceId();
        Document document = documentRepository
                .findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, target.getWorkspaceId())
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        long currentVersion = document.getCurrentVersion();
        long toVersion = change.getBeforeRevision();
        if (currentVersion == toVersion) {
            throw new InvalidRestoreRequestException(
                    "문서가 이미 그 버전 내용입니다: version=" + toVersion);
        }
        return new DocumentRestorePlan(documentId, currentVersion, toVersion);
    }
}
