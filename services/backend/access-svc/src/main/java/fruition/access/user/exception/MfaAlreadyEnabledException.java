package fruition.access.user.exception;

public class MfaAlreadyEnabledException extends RuntimeException {
    public MfaAlreadyEnabledException() {
        super("다단계 인증이 이미 켜져 있습니다. 해제한 뒤 다시 등록하세요.");
    }
}
