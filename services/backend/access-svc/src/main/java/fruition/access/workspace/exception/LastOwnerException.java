package fruition.access.workspace.exception;

/** 마지막 OWNER를 강등하거나 제거하면 워크스페이스가 관리 불능이 되므로 막는다. */
public class LastOwnerException extends RuntimeException {
    public LastOwnerException(String workspaceId) {
        super("워크스페이스의 마지막 OWNER는 변경하거나 제거할 수 없습니다: workspaceId=" + workspaceId);
    }
}
