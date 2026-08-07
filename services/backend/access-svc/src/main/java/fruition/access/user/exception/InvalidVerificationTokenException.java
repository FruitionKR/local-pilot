package fruition.access.user.exception;

public class InvalidVerificationTokenException extends RuntimeException {
    public InvalidVerificationTokenException() {
        super("유효하지 않은 인증 토큰입니다.");
    }
}
