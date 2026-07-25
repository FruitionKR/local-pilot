package fruition.document.service;

import fruition.document.domain.Document;
import fruition.document.dto.DocumentPositionRequest;
import fruition.document.dto.DocumentPositionResponse;
import fruition.document.exception.HierarchyItemNotFoundException;
import fruition.document.exception.HierarchyVersionConflictException;
import fruition.document.repository.DocumentRepository;
import fruition.document.repository.FolderRepository;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 문서를 폴더 사이·최상위로 이동하고 형제 마지막에 배치한다(TASK-H003). */
@Service
public class DocumentPlacementService {

    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final DocumentRepository documentRepository;
    private final FolderRepository folderRepository;
    private final IdempotencyService idempotencyService;

    public DocumentPlacementService(WorkspaceMemberRepository workspaceMemberRepository,
                                    DocumentRepository documentRepository,
                                    FolderRepository folderRepository,
                                    IdempotencyService idempotencyService) {
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.documentRepository = documentRepository;
        this.folderRepository = folderRepository;
        this.idempotencyService = idempotencyService;
    }

    @Transactional
    public DocumentPositionResponse move(String workspaceId, String userId, String documentId,
                                         String idempotencyKey, DocumentPositionRequest request) {
        verifyMembership(workspaceId, userId);
        idempotencyService.validateKey(idempotencyKey);
        documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new HierarchyItemNotFoundException("문서를 찾을 수 없습니다."));

        UUID targetFolderId = request.folderId();
        if (targetFolderId != null
                && folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(targetFolderId, workspaceId).isEmpty()) {
            throw new HierarchyItemNotFoundException("대상 폴더를 찾을 수 없습니다.");
        }

        String scope = "PATCH:/api/workspaces/" + workspaceId + "/documents/" + documentId + "/position";
        String hash = idempotencyService.requestHash(
                String.valueOf(targetFolderId), String.valueOf(request.baseVersion()));
        Optional<DocumentPositionResponse> replay =
                idempotencyService.replay(userId, scope, idempotencyKey, hash, DocumentPositionResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        int updated = documentRepository.moveIfVersionMatches(
                documentId, workspaceId, request.baseVersion(), targetFolderId,
                nextSortOrder(workspaceId, targetFolderId), Instant.now());
        if (updated == 0) {
            throw new HierarchyVersionConflictException("문서가 이미 변경되어 이동할 수 없습니다.");
        }

        Document moved = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new HierarchyItemNotFoundException("문서를 찾을 수 없습니다."));
        DocumentPositionResponse response = new DocumentPositionResponse(
                moved.getId(), moved.getFolderId(), moved.getSortOrder(), moved.getCurrentVersion());
        idempotencyService.save(userId, scope, idempotencyKey, hash, 200, documentId, response);
        return response;
    }

    private long nextSortOrder(String workspaceId, UUID folderId) {
        long folderMax = folderRepository.findMaxSortOrder(workspaceId, folderId);
        long documentMax = documentRepository.findMaxSortOrderInFolder(workspaceId, folderId);
        return Math.max(folderMax, documentMax) + 1;
    }

    private void verifyMembership(String workspaceId, String userId) {
        if (!workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
    }
}
