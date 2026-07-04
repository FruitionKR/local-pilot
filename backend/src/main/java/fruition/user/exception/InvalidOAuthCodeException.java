package fruition.user.exception;

public class InvalidOAuthCodeException extends RuntimeException {
    public InvalidOAuthCodeException() {
        super("유효하지 않거나 만료된 OAuth 인증 code입니다.");
    }
}
