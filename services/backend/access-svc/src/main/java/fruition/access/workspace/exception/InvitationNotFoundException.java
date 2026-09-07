package fruition.access.workspace.exception;

/** 알 수 없거나 취소된 초대 토큰. 취소된 초대도 처음부터 없었던 것처럼 다뤄 토큰 탐색을 막는다. */
public class InvitationNotFoundException extends RuntimeException {
    public InvitationNotFoundException() {
        super("초대를 찾을 수 없습니다.");
    }
}
