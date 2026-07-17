package fruition.workspace.service;

import fruition.chat.service.ChatSessionService;
import fruition.document.service.DocumentService;
import fruition.user.repository.UserRepository;
import fruition.workspace.domain.Workspace;
import fruition.workspace.domain.WorkspaceMember;
import fruition.workspace.dto.WorkspaceCreateRequest;
import fruition.workspace.dto.WorkspaceListResponse;
import fruition.workspace.dto.WorkspaceRenameRequest;
import fruition.workspace.dto.WorkspaceResponse;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import fruition.workspace.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;
    private final DocumentService documentService;
    private final ChatSessionService chatSessionService;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                            WorkspaceMemberRepository workspaceMemberRepository,
                            UserRepository userRepository,
                            DocumentService documentService,
                            ChatSessionService chatSessionService) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
        this.documentService = documentService;
        this.chatSessionService = chatSessionService;
    }

    @Transactional
    public Workspace createDefault(String userId, String displayName) {
        return createWorkspace(userId, displayName + "의 워크스페이스");
    }

    @Transactional
    public WorkspaceResponse create(String userId, WorkspaceCreateRequest request) {
        Workspace workspace = createWorkspace(userId, request.name().trim());
        return toResponse(workspace);
    }

    public WorkspaceListResponse list(String userId) {
        return new WorkspaceListResponse(
                workspaceMemberRepository.findAllWorkspacesByUserId(userId).stream()
                        .map(this::toResponse)
                        .toList()
        );
    }

    @Transactional
    public WorkspaceResponse rename(String userId, String workspaceId, WorkspaceRenameRequest request) {
        Workspace workspace = findOwned(userId, workspaceId);
        workspace.rename(request.name().trim());
        return toResponse(workspace);
    }

    @Transactional
    public void delete(String userId, String workspaceId) {
        Workspace workspace = findOwned(userId, workspaceId);

        // workspace_id FK CASCADE(V3 마이그레이션)로 하위 리소스 row는 DB에서 연쇄 삭제된다
        // (documents/wiki_pages(concept 포함)/chat_sessions 및 그 하위). 아래 호출은 DB CASCADE로
        // 대체 불가한 MinIO 오브젝트 삭제(문서 원본 등)를 위해 남긴다.
        documentService.deleteAllByWorkspaceId(workspaceId);
        chatSessionService.deleteAllByWorkspaceId(workspaceId);

        workspaceRepository.delete(workspace);
    }

    private Workspace createWorkspace(String userId, String name) {
        String workspaceId = "ws_" + UUID.randomUUID().toString().replace("-", "");
        Workspace workspace = new Workspace(workspaceId, name);
        workspaceRepository.save(workspace);

        WorkspaceMember owner = new WorkspaceMember(workspace, userRepository.getReferenceById(userId), WorkspaceMember.ROLE_OWNER);
        workspaceMemberRepository.save(owner);

        return workspace;
    }

    private Workspace findOwned(String userId, String workspaceId) {
        if (!workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
    }

    private WorkspaceResponse toResponse(Workspace workspace) {
        return new WorkspaceResponse(workspace.getId(), workspace.getName(), workspace.getCreatedAt(), workspace.getUpdatedAt());
    }
}
