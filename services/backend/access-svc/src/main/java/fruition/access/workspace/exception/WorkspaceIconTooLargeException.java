package fruition.access.workspace.exception;

public class WorkspaceIconTooLargeException extends RuntimeException {
    public WorkspaceIconTooLargeException(long maxBytes) {
        super("아이콘 이미지는 " + (maxBytes / 1024) + "KB 이하여야 합니다.");
    }
}
