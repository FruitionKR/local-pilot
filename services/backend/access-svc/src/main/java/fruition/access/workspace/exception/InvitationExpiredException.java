package fruition.access.workspace.exception;

public class InvitationExpiredException extends RuntimeException {
    public InvitationExpiredException() {
        super("초대가 만료되었습니다. 워크스페이스 소유자에게 다시 초대를 요청하세요.");
    }
}
