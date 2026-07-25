package fruition.document.service;

import fruition.document.domain.Document;
import fruition.document.domain.Folder;
import fruition.document.dto.BreadcrumbResponse;
import fruition.document.dto.FolderChildrenResponse;
import fruition.document.dto.FolderCreateRequest;
import fruition.document.dto.FolderLifecycleResponse;
import fruition.document.dto.HierarchySearchResponse;
import fruition.document.dto.FolderPositionRequest;
import fruition.document.dto.FolderRenameRequest;
import fruition.document.dto.FolderResponse;
import fruition.document.exception.HierarchyCycleException;
import fruition.document.exception.HierarchyItemNotFoundException;
import fruition.document.exception.HierarchyVersionConflictException;
import fruition.document.exception.HierarchyWriteForbiddenException;
import fruition.document.exception.InvalidHierarchyRequestException;
import fruition.document.repository.DocumentRepository;
import fruition.document.repository.FolderRepository;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class FolderService {

    private static final int MAX_NAME_LENGTH = 255;

    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final FolderRepository folderRepository;
    private final DocumentRepository documentRepository;
    private final IdempotencyService idempotencyService;
    private final SiblingReorderer siblingReorderer;

    public FolderService(WorkspaceMemberRepository workspaceMemberRepository,
                         FolderRepository folderRepository,
                         DocumentRepository documentRepository,
                         IdempotencyService idempotencyService,
                         SiblingReorderer siblingReorderer) {
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.folderRepository = folderRepository;
        this.documentRepository = documentRepository;
        this.idempotencyService = idempotencyService;
        this.siblingReorderer = siblingReorderer;
    }

    @Transactional
    public FolderResponse create(String workspaceId, String userId, String idempotencyKey,
                                 FolderCreateRequest request) {
        verifyMembership(workspaceId, userId);
        idempotencyService.validateKey(idempotencyKey);
        String name = normalizeName(request.name());
        UUID parentFolderId = request.parentFolderId();
        verifyParentFolder(workspaceId, parentFolderId);

        String scope = "POST:/api/workspaces/" + workspaceId + "/folders";
        String hash = idempotencyService.requestHash(name, String.valueOf(parentFolderId));
        Optional<FolderResponse> replay =
                idempotencyService.replay(userId, scope, idempotencyKey, hash, FolderResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        Folder folder = new Folder(
                UUID.randomUUID(), workspaceId, parentFolderId, name,
                nextSortOrder(workspaceId, parentFolderId));
        folderRepository.save(folder);
        FolderResponse response = FolderResponse.from(folder);
        idempotencyService.save(userId, scope, idempotencyKey, hash, 201, folder.getId().toString(), response);
        return response;
    }

    @Transactional
    public FolderResponse rename(String workspaceId, String userId, UUID folderId, String idempotencyKey,
                                 FolderRenameRequest request) {
        verifyMembership(workspaceId, userId);
        idempotencyService.validateKey(idempotencyKey);
        String name = normalizeName(request.name());
        requireFolder(workspaceId, folderId);

        String scope = "PATCH:/api/workspaces/" + workspaceId + "/folders/" + folderId;
        String hash = idempotencyService.requestHash(name, String.valueOf(request.baseVersion()));
        Optional<FolderResponse> replay =
                idempotencyService.replay(userId, scope, idempotencyKey, hash, FolderResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        int updated = folderRepository.renameIfVersionMatches(
                folderId, workspaceId, request.baseVersion(), name, Instant.now());
        if (updated == 0) {
            throw new HierarchyVersionConflictException("폴더가 이미 변경되어 이름을 변경할 수 없습니다.");
        }
        FolderResponse response = FolderResponse.from(requireFolder(workspaceId, folderId));
        idempotencyService.save(userId, scope, idempotencyKey, hash, 200, folderId.toString(), response);
        return response;
    }

    @Transactional
    public FolderResponse move(String workspaceId, String userId, UUID folderId, String idempotencyKey,
                               FolderPositionRequest request) {
        verifyMembership(workspaceId, userId);
        idempotencyService.validateKey(idempotencyKey);
        requireFolder(workspaceId, folderId);
        UUID targetParentId = request.parentFolderId();
        if (targetParentId != null) {
            verifyParentFolder(workspaceId, targetParentId);
            if (folderRepository.countAncestorMatches(targetParentId, folderId) > 0) {
                throw new HierarchyCycleException("폴더를 자기 자신이나 하위 폴더로 이동할 수 없습니다.");
            }
        }

        String scope = "PATCH:/api/workspaces/" + workspaceId + "/folders/" + folderId + "/position";
        String hash = idempotencyService.requestHash(
                String.valueOf(targetParentId), String.valueOf(request.position()),
                String.valueOf(request.baseVersion()));
        Optional<FolderResponse> replay =
                idempotencyService.replay(userId, scope, idempotencyKey, hash, FolderResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        long sortOrder = siblingReorderer.placeFolder(workspaceId, targetParentId, folderId, request.position());
        int updated = folderRepository.moveIfVersionMatches(
                folderId, workspaceId, request.baseVersion(), targetParentId, sortOrder, Instant.now());
        if (updated == 0) {
            throw new HierarchyVersionConflictException("폴더가 이미 변경되어 이동할 수 없습니다.");
        }
        FolderResponse response = FolderResponse.from(requireFolder(workspaceId, folderId));
        idempotencyService.save(userId, scope, idempotencyKey, hash, 200, folderId.toString(), response);
        return response;
    }

    @Transactional
    public FolderLifecycleResponse delete(String workspaceId, String userId, UUID folderId,
                                          String idempotencyKey, long baseVersion) {
        verifyMembership(workspaceId, userId);
        idempotencyService.validateKey(idempotencyKey);
        requireFolder(workspaceId, folderId);
        if (hasChildren(workspaceId, folderId) && !isWorkspaceOwner(workspaceId, userId)) {
            throw new HierarchyWriteForbiddenException("내용이 있는 폴더는 워크스페이스 소유자만 삭제할 수 있습니다.");
        }

        String scope = "DELETE:/api/workspaces/" + workspaceId + "/folders/" + folderId;
        String hash = idempotencyService.requestHash(String.valueOf(baseVersion));
        Optional<FolderLifecycleResponse> replay =
                idempotencyService.replay(userId, scope, idempotencyKey, hash, FolderLifecycleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        UUID operationId = UUID.randomUUID();
        Instant now = Instant.now();
        int updated = folderRepository.softDeleteRootIfVersionMatches(
                folderId, workspaceId, baseVersion, userId, now, operationId);
        if (updated == 0) {
            throw new HierarchyVersionConflictException("폴더가 이미 변경되어 삭제할 수 없습니다.");
        }
        folderRepository.softDeleteDescendantFolders(folderId, userId, now, operationId);
        documentRepository.softDeleteDocumentsInSubtree(folderId, userId, now, operationId);

        FolderLifecycleResponse response = new FolderLifecycleResponse(folderId, baseVersion + 1, true, now, operationId);
        idempotencyService.save(userId, scope, idempotencyKey, hash, 200, folderId.toString(), response);
        return response;
    }

    @Transactional
    public FolderLifecycleResponse restore(String workspaceId, String userId, UUID folderId,
                                           String idempotencyKey, long baseVersion) {
        verifyMembership(workspaceId, userId);
        idempotencyService.validateKey(idempotencyKey);
        Folder deleted = folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNotNull(folderId, workspaceId)
                .orElseThrow(() -> new HierarchyItemNotFoundException("삭제된 폴더를 찾을 수 없습니다."));
        UUID operationId = deleted.getDeleteOperationId();

        // 원래 부모가 아직 살아 있으면 원위치로, 삭제 상태이거나 최상위였다면 최상위 마지막으로 복구한다.
        UUID originalParent = deleted.getParentFolderId();
        boolean parentActive = originalParent == null
                || folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(originalParent, workspaceId).isPresent();
        UUID targetParent = parentActive ? originalParent : null;
        long targetSortOrder = parentActive ? deleted.getSortOrder() : nextSortOrder(workspaceId, null);

        String scope = "POST:/api/workspaces/" + workspaceId + "/folders/" + folderId + "/restore";
        String hash = idempotencyService.requestHash(String.valueOf(baseVersion));
        Optional<FolderLifecycleResponse> replay =
                idempotencyService.replay(userId, scope, idempotencyKey, hash, FolderLifecycleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        Instant now = Instant.now();
        int updated = folderRepository.restoreRootIfVersionMatches(
                folderId, workspaceId, baseVersion, targetParent, targetSortOrder, now);
        if (updated == 0) {
            throw new HierarchyVersionConflictException("폴더가 이미 변경되어 복구할 수 없습니다.");
        }
        if (operationId != null) {
            folderRepository.restoreDescendantFolders(folderId, operationId, now);
            documentRepository.restoreDocumentsInSubtree(folderId, operationId, now);
        }

        FolderLifecycleResponse response = new FolderLifecycleResponse(folderId, baseVersion + 1, false, null, null);
        idempotencyService.save(userId, scope, idempotencyKey, hash, 200, folderId.toString(), response);
        return response;
    }

    @Transactional(readOnly = true)
    public FolderChildrenResponse children(String workspaceId, String userId, UUID folderId) {
        verifyMembership(workspaceId, userId);
        if (folderId != null) {
            requireFolder(workspaceId, folderId);
        }

        List<FolderChildrenResponse.Item> items = new ArrayList<>();
        for (Folder child : folderRepository.findChildren(workspaceId, folderId)) {
            items.add(FolderChildrenResponse.Item.folder(
                    child.getId().toString(), child.getName(), child.getSortOrder(),
                    hasChildren(workspaceId, child.getId())));
        }
        for (Document child : documentRepository.findChildDocuments(workspaceId, folderId)) {
            items.add(FolderChildrenResponse.Item.document(
                    child.getId(), child.getDisplayName(), child.getSortOrder()));
        }
        items.sort(Comparator.comparingLong(FolderChildrenResponse.Item::sortOrder)
                .thenComparing(FolderChildrenResponse.Item::id));
        return new FolderChildrenResponse(items);
    }

    @Transactional(readOnly = true)
    public BreadcrumbResponse breadcrumb(String workspaceId, String userId, UUID folderId, String documentId) {
        verifyMembership(workspaceId, userId);
        if ((folderId == null) == (documentId == null)) {
            throw new InvalidHierarchyRequestException("folder_id 또는 document_id 중 하나만 지정해야 합니다.");
        }
        if (documentId != null) {
            Document document = documentRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                    .orElseThrow(() -> new HierarchyItemNotFoundException("문서를 찾을 수 없습니다."));
            List<BreadcrumbResponse.Node> nodes = folderPathNodes(document.getFolderId());
            nodes.add(BreadcrumbResponse.Node.document(document.getId(), document.getDisplayName()));
            return new BreadcrumbResponse(nodes);
        }
        requireFolder(workspaceId, folderId);
        return new BreadcrumbResponse(folderPathNodes(folderId));
    }

    @Transactional(readOnly = true)
    public HierarchySearchResponse search(String workspaceId, String userId, String query) {
        verifyMembership(workspaceId, userId);
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            throw new InvalidHierarchyRequestException("검색어를 입력해야 합니다.");
        }
        String pattern = "%" + trimmed.toLowerCase() + "%";
        List<HierarchySearchResponse.Match> results = new ArrayList<>();
        for (Folder folder : folderRepository.searchByName(workspaceId, pattern)) {
            results.add(new HierarchySearchResponse.Match(
                    "folder", folder.getId().toString(), folder.getName(),
                    folderPathNodes(folder.getParentFolderId())));
        }
        for (Document document : documentRepository.searchByName(workspaceId, pattern)) {
            results.add(new HierarchySearchResponse.Match(
                    "document", document.getId(), document.getDisplayName(),
                    folderPathNodes(document.getFolderId())));
        }
        return new HierarchySearchResponse(results);
    }

    /** 최상위부터 지정 폴더까지의 폴더 노드 목록. folderId가 null이면 빈 목록. */
    private List<BreadcrumbResponse.Node> folderPathNodes(UUID folderId) {
        List<BreadcrumbResponse.Node> nodes = new ArrayList<>();
        if (folderId == null) {
            return nodes;
        }
        for (Folder folder : folderRepository.findAncestorPath(folderId)) {
            nodes.add(BreadcrumbResponse.Node.folder(folder.getId().toString(), folder.getName()));
        }
        return nodes;
    }

    private boolean hasChildren(String workspaceId, UUID folderId) {
        return folderRepository.existsByWorkspaceIdAndParentFolderIdAndDeletedAtIsNull(workspaceId, folderId)
                || documentRepository.existsByWorkspaceIdAndFolderIdAndDeletedAtIsNull(workspaceId, folderId);
    }

    private long nextSortOrder(String workspaceId, UUID parentFolderId) {
        long folderMax = folderRepository.findMaxSortOrder(workspaceId, parentFolderId);
        long documentMax = documentRepository.findMaxSortOrderInFolder(workspaceId, parentFolderId);
        return Math.max(folderMax, documentMax) + 1;
    }

    private Folder requireFolder(String workspaceId, UUID folderId) {
        return folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(folderId, workspaceId)
                .orElseThrow(() -> new HierarchyItemNotFoundException("폴더를 찾을 수 없습니다."));
    }

    private void verifyParentFolder(String workspaceId, UUID parentFolderId) {
        if (parentFolderId != null
                && folderRepository.findByIdAndWorkspaceIdAndDeletedAtIsNull(parentFolderId, workspaceId).isEmpty()) {
            throw new HierarchyItemNotFoundException("상위 폴더를 찾을 수 없습니다.");
        }
    }

    private void verifyMembership(String workspaceId, String userId) {
        if (!workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
    }

    private boolean isWorkspaceOwner(String workspaceId, String userId) {
        return workspaceMemberRepository.findByWorkspace_IdAndUser_Id(workspaceId, userId)
                .map(member -> "OWNER".equals(member.getRole()))
                .orElse(false);
    }

    private String normalizeName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw new InvalidHierarchyRequestException("폴더 이름을 입력해야 합니다.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new InvalidHierarchyRequestException("폴더 이름은 255자 이하여야 합니다.");
        }
        return name;
    }
}
