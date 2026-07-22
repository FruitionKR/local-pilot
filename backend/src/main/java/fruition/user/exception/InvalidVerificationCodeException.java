package fruition.user.exception;

public class InvalidVerificationCodeException extends RuntimeException {
    public InvalidVerificationCodeException() {
        super("인증번호가 올바르지 않습니다.");
    }
}
