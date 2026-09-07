package fruition.access.workspace.exception;

/** 멤버지만 해당 작업에 필요한 역할(OWNER)이 아닐 때. 비멤버는 존재를 숨기려 404로 처리한다. */
public class WorkspaceAccessDeniedException extends RuntimeException {
    public WorkspaceAccessDeniedException(String message) {
        super(message);
    }
}
