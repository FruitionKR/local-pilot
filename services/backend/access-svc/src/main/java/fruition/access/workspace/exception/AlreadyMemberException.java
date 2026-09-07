package fruition.access.workspace.exception;

public class AlreadyMemberException extends RuntimeException {
    public AlreadyMemberException(String email) {
        super("이미 워크스페이스 멤버입니다: " + email);
    }
}
