package fruition.access.workspace.exception;

public class InvitationAlreadyAcceptedException extends RuntimeException {
    public InvitationAlreadyAcceptedException() {
        super("이미 수락된 초대입니다.");
    }
}
