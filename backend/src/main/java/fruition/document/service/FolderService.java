package fruition.document.service;

import fruition.document.domain.Document;
import fruition.document.domain.Folder;
import fruition.document.dto.FolderChildrenResponse;
import fruition.document.dto.FolderCreateRequest;
import fruition.document.dto.FolderPositionRequest;
import fruition.document.dto.FolderRenameRequest;
import fruition.document.dto.FolderResponse;
import fruition.document.exception.HierarchyCycleException;
import fruition.document.exception.HierarchyItemNotFoundException;
import fruition.document.exception.HierarchyVersionConflictException;
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
