package fruition.access.workspace.service;

import fruition.access.workspace.domain.Workspace;
import fruition.access.workspace.domain.WorkspaceIcon;
import fruition.access.workspace.domain.WorkspaceRole;
import fruition.access.workspace.dto.WorkspaceIconUpdateRequest;
import fruition.access.workspace.dto.WorkspaceResponse;
import fruition.access.workspace.exception.WorkspaceIconNotFoundException;
import fruition.access.workspace.exception.WorkspaceNotFoundException;
import fruition.access.workspace.repository.WorkspaceIconRepository;
import fruition.access.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 워크스페이스 아이콘. 이모지와 이미지는 배타적이라 한 곳에서 함께 다룬다.
 * 설정·삭제는 OWNER만, 조회는 멤버 전체가 할 수 있다(모든 멤버가 화면에서 아이콘을 본다).
 */
@Service
public class WorkspaceIconService {

    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceIconRepository workspaceIconRepository;
    private final WorkspaceIconValidator validator;

    public WorkspaceIconService(WorkspaceMemberRepository workspaceMemberRepository,
                                WorkspaceIconRepository workspaceIconRepository,
                                WorkspaceIconValidator validator) {
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.workspaceIconRepository = workspaceIconRepository;
        this.validator = validator;
    }

    /** 이모지로 설정한다. {@code icon_emoji}가 null이면 이미지까지 함께 지운다. */
    @Transactional
    public WorkspaceResponse updateIcon(String userId, String workspaceId, WorkspaceIconUpdateRequest request) {
        Workspace workspace = findOwned(userId, workspaceId);
        boolean hadImage = workspace.hasIconImage();
        workspace.changeIcon(request.iconEmoji());
        if (hadImage) {
            workspaceIconRepository.deleteById(workspaceId);
        }
        return WorkspaceResponse.from(workspace);
    }

    @Transactional
    public WorkspaceResponse updateIconImage(String userId, String workspaceId, MultipartFile file) {
        Workspace workspace = findOwned(userId, workspaceId);
        WorkspaceIconValidator.ValidatedIcon icon = validator.validate(file);

        workspaceIconRepository.findById(workspaceId)
                .ifPresentOrElse(
                        existing -> existing.replace(icon.bytes()),
                        () -> workspaceIconRepository.save(new WorkspaceIcon(workspaceId, icon.bytes())));
        workspace.changeIconImage(icon.contentType(), icon.hash());
        return WorkspaceResponse.from(workspace);
    }

    /** 아이콘 이미지 bytes. 멤버면 역할과 무관하게 볼 수 있다. */
    @Transactional(readOnly = true)
    public IconImage readIconImage(String userId, String workspaceId) {
        Workspace workspace = workspaceMemberRepository.findActiveWorkspaceForMember(workspaceId, userId)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
        if (!workspace.hasIconImage()) {
            throw new WorkspaceIconNotFoundException(workspaceId);
        }
        WorkspaceIcon icon = workspaceIconRepository.findById(workspaceId)
                .orElseThrow(() -> new WorkspaceIconNotFoundException(workspaceId));
        return new IconImage(icon.getImage(), workspace.getIconImageContentType(), workspace.getIconImageHash());
    }

    private Workspace findOwned(String userId, String workspaceId) {
        Workspace workspace = workspaceMemberRepository
                .findOwnedWorkspaceIncludingDeleted(workspaceId, userId, WorkspaceRole.OWNER)
                .orElseThrow(() -> new WorkspaceNotFoundException(workspaceId));
        if (workspace.getDeletedAt() != null) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
        return workspace;
    }

    public record IconImage(byte[] bytes, String contentType, String hash) {}
}
