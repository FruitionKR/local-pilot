package fruition.access.user.exception;

/** mfa_token이 없거나 만료·소비됐을 때. 로그인부터 다시 해야 한다. */
public class InvalidMfaChallengeException extends RuntimeException {
    public InvalidMfaChallengeException() {
        super("로그인 세션이 만료되었습니다. 다시 로그인해 주세요.");
    }
}
