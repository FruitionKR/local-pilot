package fruition.aihistory.service;

import fruition.aihistory.domain.ChangeType;
import fruition.aihistory.domain.OperationChange;
import fruition.aihistory.domain.OperationLog;
import fruition.aihistory.domain.ResourceType;
import fruition.aihistory.dto.DocumentRestorePlan;
import fruition.aihistory.exception.InvalidRestoreRequestException;
import fruition.aihistory.repository.OperationChangeRepository;
import fruition.document.domain.DocumentContentVersion;
import fruition.document.domain.DocumentContentVersionId;
import fruition.document.dto.DocumentContentSaveResponse;
import fruition.document.exception.DocumentContentVersionNotFoundException;
import fruition.document.repository.DocumentContentVersionRepository;
import fruition.document.service.DocumentService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문서를 예전 버전 내용으로 되돌린다.
 *
 * <p>과거 버전을 지우지 않고 <b>그 내용으로 새 버전을 쌓는다.</b> 되돌린 것도 다시 되돌릴 수 있고,
 * 버전 번호가 되돌아가 같은 번호가 다른 내용을 가리키는 일이 없다. Wiki revision과 같은 원칙이다.
 *
 * <p>저장 자체는 {@link DocumentService#saveContent}에 맡긴다. 편집 잠금·낙관적 잠금·편집 상태
 * 갱신이 이미 그 안에 있고, 되돌리기라고 다르게 처리할 이유가 없다. 적용 표를 넘기지 않으므로
 * {@code document_edit} 로그는 생기지 않는다.
 *
 * <p>문서 저장과 변경내역 기록은 <b>한 트랜잭션</b>이어야 한다. 나뉘면 문서만 바뀌고 감사 기록이
 * 없는 상태가 생긴다. {@code saveContent}가 {@code REQUIRED}라 이 트랜잭션에 참여한다.
 */
@Component
public class DocumentRestoreApplier {

    private final DocumentService documentService;
    private final DocumentContentVersionRepository contentVersionRepository;
    private final OperationChangeRepository operationChangeRepository;

    public DocumentRestoreApplier(DocumentService documentService,
                                  DocumentContentVersionRepository contentVersionRepository,
                                  OperationChangeRepository operationChangeRepository) {
        this.documentService = documentService;
        this.contentVersionRepository = contentVersionRepository;
        this.operationChangeRepository = operationChangeRepository;
    }

    /** @return 되돌리기로 만들어진 새 버전 */
    @Transactional
    public long apply(OperationLog restore, DocumentRestorePlan plan) {
        DocumentContentVersion target = contentVersionRepository
                .findById(new DocumentContentVersionId(plan.documentId(), plan.toVersion()))
                .orElseThrow(() -> new DocumentContentVersionNotFoundException(
                        plan.documentId(), plan.toVersion()));

        DocumentContentSaveResponse saved = documentService.saveContent(
                restore.getWorkspaceId(), restore.getUserId(), plan.documentId(),
                target.getMarkdown(), plan.fromVersion(), null, null);

        if (!saved.changed()) {
            throw new InvalidRestoreRequestException(
                    "문서 내용이 되돌릴 버전과 같아 변경할 것이 없습니다: version=" + plan.toVersion());
        }

        operationChangeRepository.save(new OperationChange(
                restore.getOperationId(), ResourceType.document, plan.documentId(),
                plan.fromVersion(), saved.currentVersion(), ChangeType.restored,
                "버전 " + plan.toVersion() + " 내용으로 되돌렸습니다.", null, null));

        return saved.currentVersion();
    }
}
