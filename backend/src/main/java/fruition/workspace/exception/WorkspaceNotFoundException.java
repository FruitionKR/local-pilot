package fruition.workspace.exception;

public class WorkspaceNotFoundException extends RuntimeException {
    public WorkspaceNotFoundException(String id) {
        super("워크스페이스를 찾을 수 없습니다: id=" + id);
    }
}
