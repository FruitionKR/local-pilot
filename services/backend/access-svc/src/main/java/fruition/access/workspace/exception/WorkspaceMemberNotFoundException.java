package fruition.access.workspace.exception;

public class WorkspaceMemberNotFoundException extends RuntimeException {
    public WorkspaceMemberNotFoundException(String workspaceId, String userId) {
        super("워크스페이스 멤버를 찾을 수 없습니다: workspaceId=" + workspaceId + " userId=" + userId);
    }
}
