package fruition.access.workspace.exception;

public class WorkspaceIconNotFoundException extends RuntimeException {
    public WorkspaceIconNotFoundException(String workspaceId) {
        super("워크스페이스에 설정된 아이콘 이미지가 없습니다: workspaceId=" + workspaceId);
    }
}
