package fruition.document.service;

import fruition.document.domain.Document;
import fruition.document.domain.DocumentRole;
import fruition.document.domain.SourceFolder;
import fruition.document.dto.DocumentPlacementRequest;
import fruition.document.dto.DocumentPlacementResponse;
import fruition.document.dto.DocumentTreeResponse;
import fruition.document.dto.FolderCreateRequest;
import fruition.document.dto.FolderResponse;
import fruition.document.dto.FolderUpdateRequest;
import fruition.document.exception.DocumentNotFoundException;
import fruition.document.exception.DocumentVersionConflictException;
import fruition.document.exception.DocumentWriteForbiddenException;
import fruition.document.exception.FolderNotFoundException;
import fruition.document.exception.FolderVersionConflictException;
import fruition.document.exception.InvalidFolderRequestException;
import fruition.document.repository.DocumentRepository;
import fruition.document.repository.SourceFolderRepository;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentTreeService {

    private static final int MAX_FOLDER_DEPTH = 10_000;

    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final SourceFolderRepository folderRepository;
    private final DocumentRepository documentRepository;

    public DocumentTreeService(WorkspaceMemberRepository workspaceMemberRepository,
                               SourceFolderRepository folderRepository,
                               DocumentRepository documentRepository) {
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.folderRepository = folderRepository;
        this.documentRepository = documentRepository;
    }

    @Transactional(readOnly = true)
    public DocumentTreeResponse getTree(String workspaceId, String userId) {
        verifyMembership(workspaceId, userId);
        List<FolderResponse> folders = folderRepository
                .findAllByWorkspaceIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(workspaceId)
                .stream().map(this::toFolderResponse).toList();
        List<DocumentTreeResponse.Item> documents = documentRepository
                .findVisibleByWorkspaceId(workspaceId)
                .stream().map(this::toTreeItem).toList();
        return new DocumentTreeResponse(folders, documents);
    }

    @Transactional
    public FolderResponse createFolder(String workspaceId, String userId, FolderCreateRequest request) {
        verifyMembership(workspaceId, userId);
        UUID parentId = request.parentFolderId();
        verifyParentExists(workspaceId, parentId);
        long sortOrder = request.sortOrder() != null
                ? request.sortOrder()
                : folderRepository.findMaxSortOrder(workspaceId, parentId) + 1;
        SourceFolder folder = new SourceFolder(
                UUID.randomUUID(), workspaceId, parentId, request.name().trim(), sortOrder);
        folderRepository.save(folder);
        return toFolderResponse(folder);
    }

    @Transactional
    public FolderResponse updateFolder(String workspaceId, String userId, UUID folderId, FolderUpdateRequest request) {
        verifyMembership(workspaceId, userId);
        SourceFolder folder = folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(folderId, workspaceId)
                .orElseThrow(() -> new FolderNotFoundException(folderId));
        if (folder.getCurrentVersion() != request.baseVersion()) {
            throw folderConflict();
        }
        UUID newParent = request.parentFolderId();
        verifyParentExists(workspaceId, newParent);
        if (newParent != null && wouldCreateCycle(workspaceId, folderId, newParent)) {
            throw new InvalidFolderRequestException("폴더를 자기 자신이나 하위 폴더로 이동할 수 없습니다.");
        }
        long sortOrder = request.sortOrder() != null
                ? request.sortOrder()
                : folderRepository.findMaxSortOrder(workspaceId, newParent) + 1;
        Instant now = Instant.now();
        int updated = folderRepository.updateIfVersionMatches(
                folderId, workspaceId, request.baseVersion(), request.name().trim(), newParent, sortOrder, now);
        if (updated == 0) {
            throw folderConflict();
        }
        return new FolderResponse(folderId, newParent, request.name().trim(), sortOrder, request.baseVersion() + 1);
    }

    @Transactional
    public void deleteFolder(String workspaceId, String userId, UUID folderId, Long baseVersion) {
        verifyMembership(workspaceId, userId);
        SourceFolder folder = folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(folderId, workspaceId)
                .orElseThrow(() -> new FolderNotFoundException(folderId));
        if (baseVersion != null && folder.getCurrentVersion() != baseVersion) {
            throw folderConflict();
        }
        // 하위 폴더 전체(BFS)와 그 안의 문서를 같은 delete_operation_id로 cascade 소프트 삭제한다.
        List<UUID> subtree = collectSubtreeFolderIds(workspaceId, folderId);
        UUID operationId = UUID.randomUUID();
        Instant now = Instant.now();
        folderRepository.softDeleteByIds(subtree, userId, now, operationId);
        documentRepository.softDeleteBySourceFolderIds(subtree, userId, now, operationId);
    }

    @Transactional
    public void restoreFolder(String workspaceId, String userId, UUID folderId, Long baseVersion) {
        verifyMembership(workspaceId, userId);
        SourceFolder folder = folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNotNull(folderId, workspaceId)
                .orElseThrow(() -> new FolderNotFoundException(folderId));
        if (baseVersion != null && folder.getCurrentVersion() != baseVersion) {
            throw folderConflict();
        }
        UUID operationId = folder.getDeleteOperationId();
        // 같은 삭제 작업으로 함께 지워진 폴더·문서를 그룹으로 복구한다(삭제 시점 트리·배치 보존).
        List<SourceFolder> groupFolders = operationId == null
                ? List.of(folder)
                : folderRepository.findByWorkspaceIdAndDeleteOperationIdAndDeletedAtIsNotNull(workspaceId, operationId);
        List<Document> groupDocs = operationId == null
                ? List.of()
                : documentRepository.findByWorkspaceIdAndDeleteOperationIdAndDeletedAtIsNotNull(workspaceId, operationId);

        Instant now = Instant.now();
        folderRepository.restoreByIdsPreservingTree(
                groupFolders.stream().map(SourceFolder::getId).toList(), now);
        if (operationId != null) {
            documentRepository.restoreByDeleteOperationIdPreservingPlacement(workspaceId, operationId, now);
        }

        // Fixup: 부모/폴더가 이 복구로 살아나지 않은(외부 작업으로 아직 삭제 상태인) 항목은 최상위로 뗀다.
        long nextFolderSort = folderRepository.findMaxSortOrder(workspaceId, null) + 1;
        for (SourceFolder restored : groupFolders) {
            UUID parentId = restored.getParentFolderId();
            if (parentId != null
                    && folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(parentId, workspaceId).isEmpty()) {
                folderRepository.detachToRoot(restored.getId(), workspaceId, nextFolderSort++, now);
            }
        }
        long nextDocSort = documentRepository.findMaxSortOrderInFolder(workspaceId, null) + 1;
        for (Document restored : groupDocs) {
            UUID docFolderId = restored.getSourceFolderId();
            if (docFolderId != null
                    && folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(docFolderId, workspaceId).isEmpty()) {
                documentRepository.detachToRoot(restored.getId(), workspaceId, nextDocSort++, now);
            }
        }
    }

    @Transactional
    public DocumentPlacementResponse placeDocument(
            String workspaceId, String userId, String documentId, DocumentPlacementRequest request) {
        verifyMembership(workspaceId, userId);
        Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!document.getUserId().equals(userId)) {
            throw new DocumentWriteForbiddenException("문서 소유자만 이동할 수 있습니다.");
        }
        if (document.getCurrentVersion() != request.baseVersion()) {
            throw new DocumentVersionConflictException(
                    "다른 변경이 먼저 저장되었습니다. 최신 문서를 다시 조회해 주세요.");
        }
        UUID folderId = request.folderId();
        verifyParentExists(workspaceId, folderId);
        long sortOrder = request.sortOrder() != null
                ? request.sortOrder()
                : documentRepository.findMaxSortOrderInFolder(workspaceId, folderId) + 1;
        Instant now = Instant.now();
        int updated = documentRepository.placeIfVersionMatches(
                documentId, workspaceId, request.baseVersion(), folderId, sortOrder, now);
        if (updated == 0) {
            throw new DocumentVersionConflictException(
                    "다른 변경이 먼저 저장되었습니다. 최신 문서를 다시 조회해 주세요.");
        }
        return new DocumentPlacementResponse(documentId, folderId, sortOrder, request.baseVersion() + 1);
    }

    private void verifyParentExists(String workspaceId, UUID folderId) {
        if (folderId != null
                && folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(folderId, workspaceId).isEmpty()) {
            throw new InvalidFolderRequestException("대상 폴더를 찾을 수 없습니다.");
        }
    }

    private boolean wouldCreateCycle(String workspaceId, UUID folderId, UUID newParentId) {
        UUID cursor = newParentId;
        int guard = 0;
        while (cursor != null && guard++ < MAX_FOLDER_DEPTH) {
            if (cursor.equals(folderId)) {
                return true;
            }
            cursor = folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(cursor, workspaceId)
                    .map(SourceFolder::getParentFolderId)
                    .orElse(null);
        }
        return false;
    }

    private List<UUID> collectSubtreeFolderIds(String workspaceId, UUID rootId) {
        List<UUID> ids = new ArrayList<>();
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(rootId);
        while (!queue.isEmpty() && ids.size() < MAX_FOLDER_DEPTH) {
            UUID current = queue.poll();
            ids.add(current);
            folderRepository.findByWorkspaceIdAndParentFolderIdAndDeletedAtIsNull(workspaceId, current)
                    .forEach(child -> queue.add(child.getId()));
        }
        return ids;
    }

    private void verifyMembership(String workspaceId, String userId) {
        if (!workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
    }

    private FolderVersionConflictException folderConflict() {
        return new FolderVersionConflictException(
                "폴더가 이미 변경되었습니다. 최신 상태를 다시 조회해 주세요.");
    }

    private FolderResponse toFolderResponse(SourceFolder folder) {
        return new FolderResponse(
                folder.getId(), folder.getParentFolderId(), folder.getName(),
                folder.getSortOrder(), folder.getCurrentVersion());
    }

    private DocumentTreeResponse.Item toTreeItem(Document document) {
        return new DocumentTreeResponse.Item(
                document.getId(),
                document.getFilename(),
                document.getDisplayName(),
                fileType(document.getFilename()),
                document.getDocumentRole() == DocumentRole.EDITABLE,
                document.getSourceFolderId(),
                document.getSortOrder(),
                document.getCurrentVersion());
    }

    private String fileType(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        return dot >= 0 && dot < filename.length() - 1 ? filename.substring(dot + 1).toLowerCase() : null;
    }
}
