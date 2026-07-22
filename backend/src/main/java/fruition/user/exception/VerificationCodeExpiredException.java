package fruition.user.exception;

public class VerificationCodeExpiredException extends RuntimeException {
    public VerificationCodeExpiredException() {
        super("인증번호가 만료되었습니다. 다시 요청해 주세요.");
    }
}
