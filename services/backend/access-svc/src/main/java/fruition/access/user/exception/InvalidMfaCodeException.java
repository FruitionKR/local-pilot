package fruition.access.user.exception;

/** TOTP 코드나 복구 코드가 맞지 않을 때. 어느 쪽이 틀렸는지는 알려주지 않는다. */
public class InvalidMfaCodeException extends RuntimeException {
    public InvalidMfaCodeException() {
        super("인증 코드가 올바르지 않습니다.");
    }
}
