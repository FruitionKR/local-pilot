package fruition.access.workspace.exception;

/** 초대 메일 발송 실패. 가입 인증번호와 문구가 달라야 해서 별도 예외로 둔다. */
public class InvitationSendException extends RuntimeException {
    public InvitationSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
