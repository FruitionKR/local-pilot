package fruition.user.exception;

public class EmailVerificationSendException extends RuntimeException {
    public EmailVerificationSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
