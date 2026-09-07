package fruition.access.user.exception;

public class MfaNotEnabledException extends RuntimeException {
    public MfaNotEnabledException() {
        super("다단계 인증이 설정되어 있지 않습니다.");
    }
}
