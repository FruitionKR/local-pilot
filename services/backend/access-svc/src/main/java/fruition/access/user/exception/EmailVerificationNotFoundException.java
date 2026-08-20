package fruition.access.user.exception;

public class EmailVerificationNotFoundException extends RuntimeException {
    public EmailVerificationNotFoundException() {
        super("인증 요청을 찾을 수 없습니다.");
    }
}
