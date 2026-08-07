package fruition.access.user.exception;

public class VerificationCodeAttemptsExceededException extends RuntimeException {
    public VerificationCodeAttemptsExceededException() {
        super("인증번호 입력 횟수를 초과했습니다. 다시 요청해 주세요.");
    }
}
